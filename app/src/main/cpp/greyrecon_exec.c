// greyrecon-exec -- an LD_PRELOAD shim letting GreyRecon's terminal run binaries the user places
// in the app's own writable storage, despite Android's SELinux policy denying direct execution of
// anything an app writes to its own data directory at runtime (the "app_data_file" restriction,
// `untrusted_app` domain, targetSdk >= 29 -- confirmed live on-device, see GreyRecon.md).
//
// Technique: re-exec such a binary as `<system linker> <realBinary> <origArgs...>` instead of
// exec'ing it directly. SELinux only ever sees the system linker being invoked, which IS permitted
// (it's how `.so` files get loaded), and the linker loads/runs the target from userspace. This is
// the same technique Termux's own `termux-exec` (github.com/termux/termux-exec-package, Apache-2.0)
// uses to let its `pkg install`-managed binaries run -- this file is an original, independent,
// deliberately narrower reimplementation of that published algorithm (not a copy of their source,
// which is a much larger multi-file project built around their own `$PREFIX` rootfs concept that
// doesn't apply here), named to avoid any confusion with the Termux project or trademark.
//
// Scope: intercepts `execve`/`execv`/`execvp`/`execvpe` -- what a shell's own exec calls actually
// produce for real typed commands. Deliberately does NOT cover the `execl`/`execlp`/`execle`
// (variadic-argument) family or `fexecve` (fd-based exec) -- a real, known gap versus upstream
// termux-exec's full coverage, acceptable here since GreyRecon's terminal's actual command path
// (toybox `sh` typing a command) goes through execve/execvp, not those rarer forms.

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/syscall.h>

extern char **environ;

// Chosen at compile time by pointer width -- this library is built once per Android ABI (matching
// how the rest of GreyRecon's native code is already packaged), so there's no need to inspect the
// target ELF's own class at runtime to pick 32- vs 64-bit linker.
#if UINTPTR_MAX == 0xffffffffffffffffULL
#define SYSTEM_LINKER_PATH "/system/bin/linker64"
#else
#define SYSTEM_LINKER_PATH "/system/bin/linker"
#endif

#define HEADER_BUFFER_SIZE 256

__attribute__((visibility("hidden")))
static bool isUnderSystemDir(const char *path) {
    static const char *const prefixes[] = {
        "/apex/", "/odm/", "/product/", "/sbin/", "/system/", "/system_ext/", "/vendor/",
    };
    for (size_t i = 0; i < sizeof(prefixes) / sizeof(prefixes[0]); i++) {
        size_t len = strlen(prefixes[i]);
        if (strncmp(path, prefixes[i], len) == 0) return true;
    }
    return false;
}

/** Resolves a possibly-relative exec path to an absolute one using the current working directory. */
__attribute__((visibility("hidden")))
static char *toAbsolutePath(const char *path, char *out, size_t outSize) {
    if (path[0] == '/') {
        if (strlen(path) >= outSize) { errno = ENAMETOOLONG; return NULL; }
        strcpy(out, path);
        return out;
    }
    char cwd[PATH_MAX];
    if (getcwd(cwd, sizeof(cwd)) == NULL) return NULL;
    int written = snprintf(out, outSize, "%s/%s", cwd, path);
    if (written < 0 || (size_t) written >= outSize) { errno = ENAMETOOLONG; return NULL; }
    return out;
}

/** Parses a `#!interpreter [arg]` shebang line out of a file header. Returns false if not a shebang. */
__attribute__((visibility("hidden")))
static bool parseShebang(const char *header, ssize_t headerLength,
        char *interpreterOut, size_t interpreterOutSize,
        char *argOut, size_t argOutSize, bool *hasArg) {
    if (headerLength < 3 || header[0] != '#' || header[1] != '!') return false;
    const char *newline = memchr(header, '\n', (size_t) headerLength);
    if (newline == NULL) return false;

    const char *p = header + 2;
    while (p < newline && (*p == ' ' || *p == '\t')) p++;
    if (p >= newline) return false;

    const char *interpEnd = p;
    while (interpEnd < newline && *interpEnd != ' ' && *interpEnd != '\t') interpEnd++;
    size_t interpLen = (size_t) (interpEnd - p);
    if (interpLen == 0 || interpLen >= interpreterOutSize) return false;
    memcpy(interpreterOut, p, interpLen);
    interpreterOut[interpLen] = '\0';

    *hasArg = false;
    const char *argStart = interpEnd;
    while (argStart < newline && (*argStart == ' ' || *argStart == '\t')) argStart++;
    if (argStart < newline) {
        size_t argLen = (size_t) (newline - argStart);
        while (argLen > 0 && (argStart[argLen - 1] == ' ' || argStart[argLen - 1] == '\r')) argLen--;
        if (argLen > 0 && argLen < argOutSize) {
            memcpy(argOut, argStart, argLen);
            argOut[argLen] = '\0';
            *hasArg = true;
        }
    }
    return true;
}

/** The real execve(2) syscall, bypassing libc's exported `execve` symbol entirely so this never recurses into our own hook. */
__attribute__((visibility("hidden")))
static int rawExecve(const char *path, char *const argv[], char *const envp[]) {
    return (int) syscall(SYS_execve, path, argv, envp);
}

__attribute__((visibility("hidden")))
static int interceptedExecve(const char *path, char *const argv[], char *const envp[]) {
    char absolutePath[PATH_MAX];
    if (toAbsolutePath(path, absolutePath, sizeof(absolutePath)) == NULL) return -1;

    if (isUnderSystemDir(absolutePath)) {
        return rawExecve(path, argv, envp);
    }

    if (access(absolutePath, X_OK) != 0) return -1;

    int fd = open(absolutePath, O_RDONLY);
    if (fd < 0) return -1;
    char header[HEADER_BUFFER_SIZE];
    ssize_t headerLength = read(fd, header, sizeof(header) - 1);
    close(fd);
    if (headerLength < 0) return -1;

    bool isElf = headerLength >= 4 && header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F';

    char interpreter[PATH_MAX];
    char interpreterArg[PATH_MAX];
    bool hasArg = false;
    bool isShebang = !isElf && parseShebang(header, headerLength, interpreter, sizeof(interpreter), interpreterArg, sizeof(interpreterArg), &hasArg);

    if (!isElf && !isShebang) {
        errno = ENOEXEC;
        return -1;
    }

    size_t argc = 0;
    while (argv[argc] != NULL) argc++;

    if (isShebang) {
        // Recurse through ourselves as <interpreter> [interpreterArg] <origPath> <origArgv[1..]> --
        // the interpreter itself goes through the same system-dir/linker-rewrite decision, so a
        // script whose interpreter is also under our own storage still gets wrapped correctly.
        size_t newArgc = argc + 1 + (hasArg ? 1 : 0);
        char **newArgv = malloc(sizeof(char *) * (newArgc + 1));
        if (newArgv == NULL) { errno = ENOMEM; return -1; }
        size_t i = 0;
        newArgv[i++] = interpreter;
        if (hasArg) newArgv[i++] = interpreterArg;
        newArgv[i++] = (char *) path;
        for (size_t j = 1; j < argc; j++) newArgv[i++] = argv[j];
        newArgv[i] = NULL;

        int result = interceptedExecve(interpreter, newArgv, envp);
        int savedErrno = errno;
        free(newArgv);
        errno = savedErrno;
        return result;
    }

    // Real ELF under our own storage -- wrap with the system linker.
    char **newArgv = malloc(sizeof(char *) * (argc + 2));
    if (newArgv == NULL) { errno = ENOMEM; return -1; }
    newArgv[0] = argv[0]; // preserve the program's own expected argv[0]
    newArgv[1] = absolutePath;
    for (size_t j = 1; j < argc; j++) newArgv[j + 1] = argv[j];
    newArgv[argc + 1] = NULL;

    int result = rawExecve(SYSTEM_LINKER_PATH, newArgv, envp);
    int savedErrno = errno;
    free(newArgv);
    errno = savedErrno;
    return result;
}

/** $PATH search matching execvp/execvpe semantics, resolving to an absolute path before handing off to interceptedExecve(). */
__attribute__((visibility("hidden")))
static int execvpeImpl(const char *file, char *const argv[], char *const envp[]) {
    if (strchr(file, '/') != NULL) {
        return interceptedExecve(file, argv, envp);
    }

    const char *pathEnv = getenv("PATH");
    if (pathEnv == NULL || pathEnv[0] == '\0') pathEnv = "/system/bin:/system/xbin:/vendor/bin";

    char pathBuf[PATH_MAX];
    strncpy(pathBuf, pathEnv, sizeof(pathBuf) - 1);
    pathBuf[sizeof(pathBuf) - 1] = '\0';

    int lastErrno = ENOENT;
    char *saveptr = NULL;
    for (char *dir = strtok_r(pathBuf, ":", &saveptr); dir != NULL; dir = strtok_r(NULL, ":", &saveptr)) {
        char candidate[PATH_MAX];
        if (snprintf(candidate, sizeof(candidate), "%s/%s", dir, file) >= (int) sizeof(candidate)) continue;
        if (access(candidate, X_OK) != 0) continue;
        interceptedExecve(candidate, argv, envp); // never returns on success
        if (errno != ENOENT && errno != EACCES) lastErrno = errno;
    }
    errno = lastErrno;
    return -1;
}

// --- Exported symbols: LD_PRELOAD interposition targets -----------------------------------------

__attribute__((visibility("default")))
int execve(const char *path, char *const argv[], char *const envp[]) {
    return interceptedExecve(path, argv, envp);
}

__attribute__((visibility("default")))
int execv(const char *path, char *const argv[]) {
    return interceptedExecve(path, argv, environ);
}

__attribute__((visibility("default")))
int execvp(const char *file, char *const argv[]) {
    return execvpeImpl(file, argv, environ);
}

__attribute__((visibility("default")))
int execvpe(const char *file, char *const argv[], char *const envp[]) {
    return execvpeImpl(file, argv, envp);
}

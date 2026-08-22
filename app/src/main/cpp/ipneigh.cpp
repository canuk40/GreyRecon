// Queries the kernel's ARP/IPv6-neighbor table via a netlink route socket
// (RTM_GETNEIGH) -- the standard, publicly-documented Linux rtnetlink
// mechanism (see `man 7 rtnetlink`), the same one the `ip neigh show`
// command itself uses. Reimplemented from that public protocol
// specification, not from any decompiled/reverse-engineered binary.
//
// Exists because reading /proc/net/arp as a text file is blocked for
// regular (non-privileged) apps on Android 10+ -- confirmed via
// `adb shell run-as com.greyrecon.app cat /proc/net/arp` returning
// "Permission denied".
//
// Confirmed via live device testing (see GreyRecon.md) that on at least
// one real Android build, creating this socket succeeds but sendmsg()
// on it returns EPERM -- so this is blocked too, just at a different
// syscall than the file-read version. Kept anyway: SELinux/seccomp policy
// varies by OEM and Android version, so this may still work on other
// devices even though it fails gracefully (empty result, no crash) here.
//
// IPv6 neighbor entries (RFC 4861 Neighbor Discovery Protocol -- the IPv6
// analog of ARP) included as of SESSION 22, closing a gap this file used to
// carry deliberately (`ndm_family = AF_INET` was explicitly IPv4-only).
// `ndm_family = AF_UNSPEC` in a dump request returns both families in one
// pass -- the same thing plain `ip neigh show` already does, which is why
// this file's own fallback path could already surface IPv6 neighbors
// without anyone noticing; this closes the gap in the primary netlink path
// too. Algorithm/wire-format reference: `mdlayher/ndp` (MIT), a correct Go
// NDP implementation -- read for the protocol shape, not vendored (Go, not
// C++, and RTM_GETNEIGH dump doesn't need NDP's own solicit/advertise
// packets at all, since the kernel already maintains this table for us).

#include <jni.h>
#include <string>
#include <cstring>
#include <unistd.h>
#include <sys/socket.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <linux/neighbour.h>
#include <arpa/inet.h>
#include <android/log.h>

#define LOG_TAG "ipneigh"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace {

struct NeighRequest {
    nlmsghdr nlh;
    ndmsg ndm;
};

// States that represent a real, currently-known neighbor entry -- mirrors
// the /proc/net/arp version's "skip incomplete/failed entries" filter.
constexpr int kValidStates = NUD_REACHABLE | NUD_STALE | NUD_PERMANENT | NUD_DELAY | NUD_PROBE;

std::string queryNeighborTable() {
    int sock = socket(AF_NETLINK, SOCK_RAW, NETLINK_ROUTE);
    if (sock < 0) {
        LOGD("socket(AF_NETLINK) failed: errno=%d (%s)", errno, strerror(errno));
        return "";
    }

    sockaddr_nl sa{};
    sa.nl_family = AF_NETLINK;

    NeighRequest req{};
    req.nlh.nlmsg_len = NLMSG_LENGTH(sizeof(ndmsg));
    req.nlh.nlmsg_type = RTM_GETNEIGH;
    req.nlh.nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP;
    req.nlh.nlmsg_seq = 1;
    req.nlh.nlmsg_pid = static_cast<uint32_t>(getpid());
    req.ndm.ndm_family = AF_UNSPEC; // both IPv4 (ARP) and IPv6 (NDP) neighbor entries in one dump

    iovec iov{&req, req.nlh.nlmsg_len};
    msghdr msg{};
    msg.msg_name = &sa;
    msg.msg_namelen = sizeof(sa);
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;

    if (sendmsg(sock, &msg, 0) < 0) {
        LOGD("sendmsg failed: errno=%d (%s)", errno, strerror(errno));
        close(sock);
        return "";
    }

    std::string result;
    char buf[8192];
    bool done = false;

    while (!done) {
        ssize_t len = recv(sock, buf, sizeof(buf), 0);
        if (len <= 0) {
            break;
        }

        auto* nlh = reinterpret_cast<nlmsghdr*>(buf);
        auto remaining = static_cast<size_t>(len);

        while (NLMSG_OK(nlh, remaining)) {
            if (nlh->nlmsg_type == NLMSG_DONE || nlh->nlmsg_type == NLMSG_ERROR) {
                done = true;
                break;
            }

            if (nlh->nlmsg_type == RTM_NEWNEIGH) {
                auto* ndm = static_cast<ndmsg*>(NLMSG_DATA(nlh));
                auto* rta = reinterpret_cast<rtattr*>(
                    reinterpret_cast<char*>(ndm) + NLMSG_ALIGN(sizeof(ndmsg)));
                int rtaLen = static_cast<int>(NLMSG_PAYLOAD(nlh, sizeof(ndmsg)));

                // INET6_ADDRSTRLEN (46) covers both families -- using the smaller IPv4-only
                // INET_ADDRSTRLEN here would truncate/corrupt every IPv6 address written into it.
                char ip[INET6_ADDRSTRLEN] = {0};
                char mac[18] = {0};
                bool hasIp = false;
                bool hasMac = false;

                while (RTA_OK(rta, rtaLen)) {
                    if (rta->rta_type == NDA_DST) {
                        // ndm->ndm_family tells us which family *this* entry actually is (the
                        // request's own ndm_family is just AF_UNSPEC, "give me both") -- NDA_DST's
                        // raw bytes are 4 or 16 long to match, so inet_ntop must be told which.
                        inet_ntop(ndm->ndm_family, RTA_DATA(rta), ip, sizeof(ip));
                        hasIp = true;
                    } else if (rta->rta_type == NDA_LLADDR) {
                        auto* macBytes = static_cast<unsigned char*>(RTA_DATA(rta));
                        snprintf(mac, sizeof(mac), "%02x:%02x:%02x:%02x:%02x:%02x",
                                 macBytes[0], macBytes[1], macBytes[2],
                                 macBytes[3], macBytes[4], macBytes[5]);
                        hasMac = true;
                    }
                    rta = RTA_NEXT(rta, rtaLen);
                }

                if (hasIp && hasMac && (ndm->ndm_state & kValidStates)) {
                    result += ip;
                    result += ",";
                    result += mac;
                    result += "\n";
                }
            }

            nlh = NLMSG_NEXT(nlh, remaining);
        }
    }

    close(sock);
    return result;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_greyrecon_app_engine_discovery_NativeArpScanner_queryNeighborTable(
    JNIEnv* env, jobject /* thiz */) {
    return env->NewStringUTF(queryNeighborTable().c_str());
}

#!/system/bin/sh
# help -- GreyRecon terminal overview.
#
# Deliberately shadows toybox's own `help` applet (see setupToyboxBinDir() in TerminalScreen.kt,
# which skips creating that symlink once this file already occupies the name): bare `help` with no
# arguments gives a one-screen overview of everything available in *this* terminal, not just one
# toybox applet's usage. Nothing is actually lost -- `help <command>` still delegates straight through
# to toybox's own real per-command help.

if [ -n "$1" ]; then
  exec toybox help "$@"
fi

cat <<'EOF'
GreyRecon terminal -- quick reference

  A real interactive shell (/system/bin/sh), backed by a bundled toybox
  (github.com/landley/toybox, 0BSD) providing ~190 standard Unix utilities,
  plus greyrecon-pkg, an original package installer for anything else.

COMMANDS

  help [command]      This overview, or toybox's own help for one command
  toybox               List every bundled command by name
  <command> --help     Usage for one specific bundled command

AI ASSISTANT

  ai <question>        Ask GreyRecon's on-device AI agent, which runs the real
                       recon tools (scan the network, look up CVEs, check CISA
                       KEV, WHOIS, and more) to answer. Uses the AI provider +
                       API key set in GreyRecon's Settings.
                       e.g.  ai what's on my network and is anything exposed?

PACKAGES (greyrecon-pkg)

  greyrecon-pkg install <file.grpkg | url> [sha256sum]   Install a package
  greyrecon-pkg fetch <url> <dest> [sha256sum]           Download a file
  greyrecon-pkg list                                     List installed packages
  greyrecon-pkg remove <name>                            Uninstall a package
  greyrecon-pkg info <name>                               Show package details

  See GreyRecon.md for the .grpkg format and how checksums are verified.

NOTES

  $HOME/bin is first on $PATH, so bundled/installed tools shadow Android's
  own /system/bin versions of the same name.

  GreyRecon never bundles nmap or other restrictively-licensed tools itself --
  bring your own binary (a Termux install, something you compiled yourself)
  and greyrecon-pkg, or plain cp + chmod, can make it runnable from here.
EOF

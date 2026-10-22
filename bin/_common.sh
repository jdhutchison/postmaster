# Shared setup for the bin/ scripts. Source it; don't run it.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1

# Use SDKMAN's JDK when java isn't already on the PATH
if ! command -v java >/dev/null 2>&1 && [ -s "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
  # SDKMAN's init script doesn't work with the scripts' strict options
  set +eu
  # shellcheck disable=SC1091
  source "$HOME/.sdkman/bin/sdkman-init.sh"
  set -eu
fi

if ! command -v java >/dev/null 2>&1; then
  echo "Java not found. Install GraalVM for JDK 25 (e.g. with SDKMAN) and try again." >&2
  exit 1
fi

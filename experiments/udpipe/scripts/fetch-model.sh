#!/usr/bin/env bash
#
# Download a UDPipe UD-2.5 model by name into models/.
#
#   scripts/fetch-model.sh slovak-snk     -> models/slovak-snk.udpipe
#   scripts/fetch-model.sh czech-pdt      -> models/czech-pdt.udpipe
#
# Source: the jwijffels/udpipe.models.ud.2.5 GitHub mirror (raw, stable links).
# The canonical LINDAT repository is now a JavaScript SPA with no stable direct
# download URLs, so the mirror (used by the R 'udpipe' package) is more reliable.
#
# License of the models: CC BY-NC-SA (non-commercial). Fine for research / personal use.
#
set -euo pipefail

NAME="${1:?usage: fetch-model.sh <model-basename>   e.g. slovak-snk | czech-pdt | english-ewt}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${ROOT}/models"
TAG="udpipe-ud-2.5-191206"
BASE="https://raw.githubusercontent.com/jwijffels/udpipe.models.ud.2.5/master/inst/${TAG}"

mkdir -p "$DEST"
url="${BASE}/${NAME}-${TAG}.udpipe"
out="${DEST}/${NAME}.udpipe"

echo ">> downloading ${url}"
curl -fsSL -o "$out" "$url"
echo "-> ${out} ($(du -h "$out" | cut -f1))"

#!/usr/bin/env bash
set -eu

usage() {
    echo "usage: GS_URL=http://host/geoserver GS_AUTH=user:password $0 [-d] <sld-dir> <workspace>" >&2
    echo "  -d  set each style as default style of layer <workspace>:<basename>" >&2
    exit 2
}

set_default=0
if [ "${1:-}" = "-d" ]; then
    set_default=1
    shift
fi
[ $# -eq 2 ] || usage
dir=$1
ws=$2
: "${GS_URL:?set GS_URL to the GeoServer base URL, without /mcp}"
: "${GS_AUTH:?set GS_AUTH to user:password}"
[ -d "$dir" ] || { echo "not a directory: $dir" >&2; exit 2; }

body=$(mktemp)
trap 'rm -f "$body"' EXIT

first_line() {
    sed -n '/[^[:space:]]/{s/^[[:space:]]*//;p;q;}' "$body" | sed 's/<[^>]*>//g' | cut -c1-160
}

status() {
    curl -s -o "$body" -w '%{http_code}' -u "$GS_AUTH" "$@"
}

found=0
for file in "$dir"/*.sld; do
    [ -e "$file" ] || continue
    found=1
    name=$(basename "$file" .sld)
    # GeoServer parses SLD 1.1 as 1.0 unless the SE content type is sent.
    if head -c 4096 "$file" | grep -q 'version="1\.1\.0"'; then
        ctype='application/vnd.ogc.se+xml'
    else
        ctype='application/vnd.ogc.sld+xml'
    fi
    styles="$GS_URL/rest/workspaces/$ws/styles"
    # POST on an existing style answers 403, so existence decides the verb.
    if [ "$(status "$styles/$name.json")" = 200 ]; then
        verb=PUT
        code=$(status -X PUT -H "Content-Type: $ctype" --data-binary @"$file" "$styles/$name")
    else
        verb=POST
        code=$(status -X POST -H "Content-Type: $ctype" --data-binary @"$file" "$styles?name=$name")
    fi
    line="$name $verb $code"
    case $code in 2*) ;; *) line="$line $(first_line)" ;; esac
    if [ $set_default -eq 1 ] && [ "${code#2}" != "$code" ]; then
        dcode=$(status -X PUT -H 'Content-Type: application/json' \
            -d "{\"layer\":{\"defaultStyle\":{\"name\":\"$ws:$name\"}}}" "$GS_URL/rest/layers/$ws:$name")
        line="$line default $dcode"
        case $dcode in 2*) ;; *) line="$line $(first_line)" ;; esac
    fi
    echo "$line"
done
[ $found -eq 1 ] || { echo "no *.sld in $dir" >&2; exit 1; }

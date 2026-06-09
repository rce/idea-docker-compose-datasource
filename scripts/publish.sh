#!/usr/bin/env bash
#
# Interactive sign/publish helper for the JetBrains Marketplace.
#
#   - Generates (or reuses) a self-signed plugin signing certificate.
#   - Prompts for the passphrase, Marketplace token and channel.
#   - Either signs the plugin (for a first, manual web upload) or signs AND
#     publishes it to the Marketplace.
#
# Signing material is kept in .secrets/ (git-ignored). The private key is
# passphrase-encrypted; the passphrase and token are never written to disk.
#
set -euo pipefail

cd "$(dirname "$0")/.."

SECRETS_DIR=".secrets"
CHAIN="$SECRETS_DIR/chain.crt"
KEY="$SECRETS_DIR/private.pem"

bold() { printf '\033[1m%s\033[0m\n' "$1"; }
die() { printf '\033[31m%s\033[0m\n' "$1" >&2; exit 1; }

command -v openssl >/dev/null || die "openssl is required but not found."
[ -x ./gradlew ] || die "./gradlew not found — run this from the plugin repo."
mkdir -p "$SECRETS_DIR"

# --- 1. Signing certificate -------------------------------------------------
KEY_PW=""
if [ -f "$CHAIN" ] && [ -f "$KEY" ]; then
  bold "Using existing signing certificate in $SECRETS_DIR/."
else
  bold "No signing certificate found in $SECRETS_DIR/."
  read -r -p "Generate a new self-signed certificate now? [Y/n] " ans
  [[ "${ans:-Y}" =~ ^[Yy]$ ]] || die "Place chain.crt and private.pem in $SECRETS_DIR/ and re-run."

  read -r -p "Certificate name (CN) [Henry Heikkinen]: " CN
  CN=${CN:-Henry Heikkinen}
  read -r -s -p "Choose a passphrase for the new private key: " KEY_PW; echo
  read -r -s -p "Confirm passphrase: " KEY_PW2; echo
  [ "$KEY_PW" = "$KEY_PW2" ] || die "Passphrases do not match."
  [ -n "$KEY_PW" ] || die "Passphrase must not be empty."

  openssl genpkey -aes-256-cbc -algorithm RSA -pkeyopt rsa_keygen_bits:4096 \
    -pass "pass:$KEY_PW" -out "$KEY"
  openssl req -key "$KEY" -passin "pass:$KEY_PW" -new -x509 -days 3650 \
    -subj "/CN=$CN" -out "$CHAIN"
  chmod 600 "$KEY"
  bold "Created $KEY and $CHAIN (keep these safe — you need the same key for every update)."
fi

if [ -z "$KEY_PW" ]; then
  read -r -s -p "Private key passphrase: " KEY_PW; echo
fi

# --- 2. What to do ----------------------------------------------------------
bold ""
bold "What would you like to do?"
echo "  1) Sign only            — produces a *-signed.zip for the first manual upload"
echo "  2) Sign and publish     — uploads to the Marketplace (for updates)"
read -r -p "Choice [1/2]: " choice

export CERTIFICATE_CHAIN; CERTIFICATE_CHAIN="$(cat "$CHAIN")"
export PRIVATE_KEY; PRIVATE_KEY="$(cat "$KEY")"
export PRIVATE_KEY_PASSWORD="$KEY_PW"

case "$choice" in
  1)
    bold "Signing plugin…"
    ./gradlew signPlugin
    echo
    bold "Signed archive:"
    ls -1 build/distributions/*-signed.zip
    echo "Upload this on https://plugins.jetbrains.com (Upload plugin) for the first release."
    ;;
  2)
    read -r -s -p "JetBrains Marketplace token (PUBLISH_TOKEN): " PUBLISH_TOKEN; echo
    [ -n "$PUBLISH_TOKEN" ] || die "Token is required."
    export PUBLISH_TOKEN
    read -r -p "Channel [default]: " CHANNEL
    CHANNEL=${CHANNEL:-default}

    read -r -p "Run verifyPlugin first? (slow — downloads IDEs) [y/N] " v
    if [[ "${v:-N}" =~ ^[Yy]$ ]]; then
      bold "Verifying…"; ./gradlew verifyPlugin
    fi

    bold "Signing and publishing to '$CHANNEL'…"
    if [ "$CHANNEL" = "default" ]; then
      ./gradlew publishPlugin
    else
      ./gradlew publishPlugin -PpublishChannel="$CHANNEL"
    fi
    bold "Done. New versions appear after Marketplace processing (first version needs one-time review)."
    ;;
  *)
    die "Unknown choice: $choice"
    ;;
esac

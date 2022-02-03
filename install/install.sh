#!/bin/bash

#
# Copyright (c) 2021, 2022 - Yupiik SAS - https://www.yupiik.com
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#  http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
set -e

CENTRAL="https://repo.maven.apache.org/maven2"
GROUP_ID="io/yupiik"
ARTIFACT_ID="bundlebee-core"
if [ -z "$BUNDLEBEE_DIR" ]; then
    BUNDLEBEE_DIR="$HOME/.yupiik/bundlebee"
fi

echo '                                                                           '
echo '            ██╗   ██╗██╗   ██╗██████╗ ██╗██╗██╗  ██╗                       '
echo '            ╚██╗ ██╔╝██║   ██║██╔══██╗██║██║██║ ██╔╝                       '
echo '             ╚████╔╝ ██║   ██║██████╔╝██║██║█████╔╝                        '
echo '              ╚██╔╝  ██║   ██║██╔═══╝ ██║██║██╔═██╗                        '
echo '               ██║   ╚██████╔╝██║     ██║██║██║  ██╗                       '
echo '               ╚═╝    ╚═════╝ ╚═╝     ╚═╝╚═╝╚═╝  ╚═╝                       '
echo '                                                                           '
echo '██████╗ ██╗   ██╗███╗   ██╗██████╗ ██╗     ███████╗██████╗ ███████╗███████╗'
echo '██╔══██╗██║   ██║████╗  ██║██╔══██╗██║     ██╔════╝██╔══██╗██╔════╝██╔════╝'
echo '██████╔╝██║   ██║██╔██╗ ██║██║  ██║██║     █████╗  ██████╔╝█████╗  █████╗  '
echo '██╔══██╗██║   ██║██║╚██╗██║██║  ██║██║     ██╔══╝  ██╔══██╗██╔══╝  ██╔══╝  '
echo '██████╔╝╚██████╔╝██║ ╚████║██████╔╝███████╗███████╗██████╔╝███████╗███████╗'
echo '╚═════╝  ╚═════╝ ╚═╝  ╚═══╝╚═════╝ ╚══════╝╚══════╝╚═════╝ ╚══════╝╚══════╝'
echo '                                                                           '
echo '                                                              Installing...'

#
# check our pre-requisites
#

uname_value="$(uname)"
if [[ "$uname_value" != "Linux" ]]; then
	echo "$uname_value not yet supported for native mode, please follow JVM mode installation or build it from sources."
	exit 1
fi
if [ -z $(which curl) ]; then
	echo "Curl not found, ensure you have it on your system before using that script please."
	exit 2
fi
if [ -z $(which grep) ]; then
	echo "Grap not found, ensure you have it on your system before using that script please."
	exit 3
fi
if [ -z $(which sed) ]; then
	echo "Sed not found, ensure you have it on your system before using that script please."
	exit 4
fi
if [ -z $(which head) ]; then
	echo "Head not found, ensure you have it on your system before using that script please."
	exit 5
fi

#
# install
#

echo "Ensuring $BUNDLEBEE_DIR exists..."
mkdir -p "$BUNDLEBEE_DIR/bin"

base="$CENTRAL/$GROUP_ID/$ARTIFACT_ID"
last_release="$(curl --fail  --silent "$base/maven-metadata.xml" -o - | grep latest | head -n 1 | sed 's/.*>\([^<]*\)<.*/\1/')"
binary="$BUNDLEBEE_DIR/bin/bundlebee"

echo "Downloading yupiik BundleBee..."
curl --fail --location --progress-bar "$base/$last_release/$ARTIFACT_ID-$last_release-Linux-amd64.bin" > "$binary" && \
  chmod +x "$binary"

echo -e "\n\n\nBundleBee installed!\nYou can now add $BUNDLEBEE_DIR/bin to your PATH variable (in your ~/bashrc or so).\n\n"


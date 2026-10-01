#!/bin/bash
# Automated test on a virtual phone with a real (tiny) AI model. Keeps going on errors so we see everything.
PKG=com.onlypanels.lucifer
OUT=ui-results
mkdir -p $OUT
UI="python3 .github/uitest/ui.py"
TEST_MODEL="https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
n=0
shot() { sleep 2; n=$((n+1)); f=$(printf "%s/%02d-%s.png" $OUT $n "$1"); adb exec-out screencap -p > "$f"; echo "screenshot $f"; $UI texts > "${f%.png}.txt"; }
hook() { adb shell am broadcast -n $PKG/.TestHooks "$@" | grep -o 'data=.*'; }
main() { adb shell am start -W -n $PKG/.MainActivity > /dev/null; sleep 2; }
back() { adb shell input keyevent 4; sleep 1; }
swipe() { adb shell input swipe 540 1800 540 600 500; }
type_msg() {  # types a message into the box and sends it
  $UI tap "Message Lucifer"
  adb shell input text "$(echo "$1" | sed 's/ /%s/g; s/?/\\?/g; s/'"'"'/\\'"'"'/g')"
  sleep 1
  adb shell input keyevent 111   # hide keyboard
  $UI tapx "Send"
}
wait_reply() {  # waits until the Stop button turns back into Send
  sleep 3
  $UI waitgone "Stop" "$1"
  sleep 2
}

echo "test model link: $(curl -sIL -o /dev/null -w '%{http_code}' $TEST_MODEL)" | tee -a $OUT/model-links.txt

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c

# 1. First launch: Lucifer starts fetching its own brain automatically
main;                                       shot first-launch-auto-download
sleep 10;                                   shot first-launch-progress
hook --es task state
hook --es task canceldl                     # too big for the virtual phone; use a tiny test model instead
main; sleep 2;                              shot first-launch-manual
$UI tap "Choose a different model";         shot settings-top
swipe;                                      shot settings-model
$UI tap "Download a model";                 shot download-dialog
back
swipe;                                      shot settings-privacy
swipe;                                      shot settings-finetune
swipe;                                      shot settings-bottom
back

# 2. Download a tiny model through the app's own downloader
hook --es task download --es url "$TEST_MODEL"
settings() { main; $UI tapx "Settings"; sleep 2; }
settings
sleep 6; swipe;                             shot downloading
for i in $(seq 1 60); do
  s=$(hook --es task state); echo "$s"
  echo "$s" | grep -q "download=null" && break
  sleep 5
done
sleep 3
# fallback so the rest of the test can still run if the download failed
if ! adb shell ls /sdcard/Android/data/$PKG/files/models/ | grep -q gguf; then
  echo "DOWNLOAD FAILED - pushing model directly" | tee -a $OUT/summary-notes.txt
  curl -sL -o /tmp/m.gguf "$TEST_MODEL"
  adb shell mkdir -p /sdcard/Android/data/$PKG/files/models
  adb push /tmp/m.gguf /sdcard/Android/data/$PKG/files/models/test.gguf
  hook --es task usemodel --es path /sdcard/Android/data/$PKG/files/models/test.gguf
fi
sleep 5
back
settings; swipe;                            shot model-ready
back

# start Tor now so it has plenty of time to connect before the private test
hook --es task tor --ez on true

# 3. Chat offline (web off)
hook --es task web --ez on false
hook --es task temp --ef value 0.3
main;                                       shot ready-greeting
type_msg "Write a short two line poem about the sea"
sleep 2;                                    shot thinking
wait_reply 240;                             shot reply-offline
hook --es task state

# 4. Follow-up in the same chat (tests memory of the conversation)
type_msg "Now make it about the moon instead"
wait_reply 240;                             shot reply-followup

# 6. Private mode (built-in Tor) — switched on before step 3
hook --es task web --ez on true
echo "real address of the test machine: $(curl -s https://api.ipify.org)" | tee $OUT/tor-check.txt
echo "tor phase: $(hook --es task torphase)" | tee -a $OUT/tor-check.txt
settings
swipe; swipe;                               shot privacy-connecting
echo "tor address 1: $(hook --es task torip)" | tee -a $OUT/tor-check.txt
echo "tor phase: $(hook --es task torphase)" | tee -a $OUT/tor-check.txt
settings; swipe; swipe; sleep 20;           shot privacy-connected
hook --es task newid | tee -a $OUT/tor-check.txt
sleep 10
echo "tor address 2 (after new location): $(hook --es task torip)" | tee -a $OUT/tor-check.txt
back
main
$UI tapx "New chat"
type_msg "What is the capital city of Malta?"
sleep 5;                                    shot private-searching
wait_reply 360;                             shot reply-private
hook --es task state

# 5. Web search on, new chat (normal, not private)
hook --es task tor --ez on false
hook --es task web --ez on true
hook --es task web --ez on true
$UI tapx "New chat";                        shot new-chat-web-on
type_msg "Who won the 2022 FIFA World Cup final?"
sleep 4;                                    shot searching
wait_reply 300;                             shot reply-web
swipe;                                      shot reply-web-sources


# 7. Chats list, options, voice button
$UI tapx "Chats";                           shot chats-list
$UI longtap "World Cup";                    shot chat-options
back; back
main
$UI tapx "Speak";                           shot voice
back

# 8. Long-press copy menu on a reply
main
$UI longtap "Argentina";                    shot copy-menu
back

# 9. Relaunch to be sure everything survives a restart
adb shell am force-stop $PKG
main; sleep 10;                             shot after-restart

adb logcat -d > $OUT/logcat.txt
grep -E "FATAL|E AndroidRuntime|UITEST|LuciferTor|LuciferWeb|Lucifer  |Bootstrapped" $OUT/logcat.txt | head -300 > $OUT/summary.txt
echo "---- summary ----"; cat $OUT/summary.txt | head -80
exit 0

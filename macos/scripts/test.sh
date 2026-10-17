#!/bin/bash
set -euo pipefail
mac_dir=$(cd "$(dirname "$0")/.." && pwd)
arch=${1:?architecture required}
qa="$mac_dir/qa/$arch"
mkdir -p "$qa"
if [ "${GITHUB_ACTIONS:-}" != true ] && [ "${MODELATLAS_ALLOW_TEST_CERT:-}" != 1 ]; then
    echo 'This integration harness temporarily trusts a fresh local TLS fixture CA. Run in disposable CI, or opt in with MODELATLAS_ALLOW_TEST_CERT=1.' >&2
    exit 1
fi
(cd "$mac_dir/dist" && shasum -a 256 -c SHA256SUMS.txt)
ditto -x -k "$mac_dir/dist/ModelAtlas-macOS-Universal.zip" "$qa/app"
app="$qa/app/ModelAtlas.app"
codesign --verify --deep --strict "$app"
lipo "$app/Contents/MacOS/ModelAtlas" -verify_arch "$arch"
lipo "$app/Contents/MacOS/ModelAtlasEngine" -verify_arch "$arch"
go build -o "$qa/provider-fixture" "$mac_dir/Fixtures/provider.go"
"$qa/provider-fixture" "$qa/fixture" >"$qa/fixture.log" 2>&1 &
fixture_pid=$!
trusted=0
cleanup() {
    original_status=$?
    trap - EXIT
    cleanup_failed=0
    kill "$fixture_pid" 2>/dev/null || true
    wait "$fixture_pid" 2>/dev/null || true
    if [ "$trusted" = 1 ]; then
        echo 'Native stage: revoke disposable fixture trust and delete its exact certificate'
        # remove-trusted-cert can open SecurityAgent in headless macOS runners
        # (actions/runner-images#12116). Deny the exact fresh CA instead, then
        # delete only its certificate; never change authorizationdb / TLS checks.
        python3 "$mac_dir/scripts/run-bounded.py" 30 "$qa/revoke-fixture-trust.log" sudo security add-trusted-cert -d -r deny -k /Library/Keychains/System.keychain "$qa/fixture/ca.pem" || cleanup_failed=1
        python3 "$mac_dir/scripts/run-bounded.py" 45 "$qa/verify-fixture-denied.log" swift "$mac_dir/scripts/verify-revoked.swift" "$qa/fixture/ca.pem" || cleanup_failed=1
        python3 "$mac_dir/scripts/run-bounded.py" 30 "$qa/remove-fixture-cert.log" sudo security delete-certificate -Z "$fingerprint" /Library/Keychains/System.keychain || cleanup_failed=1
        python3 "$mac_dir/scripts/run-bounded.py" 45 "$qa/verify-fixture-revoked.log" swift "$mac_dir/scripts/verify-revoked.swift" "$qa/fixture/ca.pem" --after-delete || cleanup_failed=1
    fi
    if [ "$original_status" != 0 ]; then exit "$original_status"; fi
    exit "$cleanup_failed"
}
trap cleanup EXIT
for i in $(seq 1 100); do
    if [ -s "$qa/fixture/fixture.json" ]; then break; fi
    sleep 0.1
done
fingerprint=$(openssl x509 -in "$qa/fixture/ca.pem" -noout -fingerprint -sha1 | cut -d= -f2 | tr -d :)
echo 'Native stage: trust disposable local TLS fixture'
python3 "$mac_dir/scripts/run-bounded.py" 45 "$qa/trust-fixture.log" sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain "$qa/fixture/ca.pem"
trusted=1
export MODELATLAS_ENGINE_PATH="$app/Contents/MacOS/ModelAtlasEngine"
export MODELATLAS_FIXTURE_URL
MODELATLAS_FIXTURE_URL=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["url"])' "$qa/fixture/fixture.json")
export MODELATLAS_PLAIN_FIXTURE_URL
MODELATLAS_PLAIN_FIXTURE_URL=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["plain"])' "$qa/fixture/fixture.json")
echo 'Native stage: build test bundle'
python3 "$mac_dir/scripts/run-bounded.py" 480 "$qa/swift-test-build.log" swift build --package-path "$mac_dir" --build-tests
echo 'Native stage: run packaged engine and Keychain tests'
python3 "$mac_dir/scripts/run-bounded.py" 300 "$qa/swift-tests.log" swift test --package-path "$mac_dir" --skip-build --parallel
echo 'Native stage: launch actual SwiftUI app and measure process footprint'
python3 - "$app" "$qa" "$MODELATLAS_FIXTURE_URL" <<'PY'
import json,pathlib,subprocess,sys,time
app,qa,fixture=sys.argv[1:]
out=pathlib.Path(qa)/'ui'
out.mkdir(parents=True,exist_ok=True)
with (pathlib.Path(qa)/'app-launch.log').open('w') as log:
    command=[str(pathlib.Path(app)/'Contents/MacOS/ModelAtlas'),'--smoke-dir',str(out),'--fixture-url',fixture]
    if pathlib.Path(qa).name=='x86_64': command.append('--smoke-close-window')
    process=subprocess.Popen(command,stdout=log,stderr=log)
    deadline=time.monotonic()+120
    peaks={'app_rss_bytes':0,'engine_rss_bytes':0,'combined_rss_bytes':0,'samples':0,'method':'ps RSS of the standalone native app and its bundled engine; excludes fixtures/test runners; startup, TLS chat, scoped draft/search guards and twenty-five native window/sheet captures'}
    try:
        while process.poll() is None and time.monotonic()<deadline:
            pid_file=out/'measurement-pids.json'
            if pid_file.exists():
                ids=json.loads(pid_file.read_text())
                values=[]
                for key in ['app_pid','engine_pid']:
                    result=subprocess.run(['ps','-o','rss=','-p',str(int(ids[key]))],capture_output=True,text=True)
                    values.append(int(result.stdout.strip() or 0)*1024)
                peaks['app_rss_bytes']=max(peaks['app_rss_bytes'],values[0])
                peaks['engine_rss_bytes']=max(peaks['engine_rss_bytes'],values[1])
                peaks['combined_rss_bytes']=max(peaks['combined_rss_bytes'],sum(values))
                peaks['samples']+=1
            time.sleep(.15)
        if process.poll() is None:
            process.kill(); process.wait(); raise RuntimeError('Native app smoke timed out')
        assert process.returncode==0,process.returncode
    finally:
        if process.poll() is None: process.kill(); process.wait()
        (pathlib.Path(qa)/'process-footprint.json').write_text(json.dumps(peaks,indent=2))
    assert peaks['app_rss_bytes']>0 and peaks['engine_rss_bytes']>0 and peaks['samples']>=3,peaks
    ids=json.loads((out/'measurement-pids.json').read_text())
    child=subprocess.run(['ps','-o','pid=','-p',str(int(ids['engine_pid']))],capture_output=True,text=True)
    assert not child.stdout.strip(),'App exited but its owned engine is still running'
report=out/'native-smoke.json'
assert report.exists(), (out/'FAILURE.txt').read_text() if (out/'FAILURE.txt').exists() else 'native app did not finish smoke'
value=json.loads(report.read_text())
assert value['result']=='PASS' and value['real_tls_chat'] and value['screenshots']==25,value
assert value['draft_scope_guards'] and value['search_scope_guards'],value
assert value['keyboard_picker_guards'] and value['live_session_search'] and value['chat_settings_guards'],value
assert value['chat_reading_guards'] and value['attachment_guards'] and value['request_receipt'] and value['native_markdown'],value
assert value['providers']==2 and value['models']==4 and value['messages']==2,value
assert len(list(out.glob('*.png')))==25
print(json.dumps(value,ensure_ascii=False))
PY


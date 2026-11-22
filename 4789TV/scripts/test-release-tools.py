#!/usr/bin/env python3
"""Offline regression checks for release staging; no GitHub writes or Android device changes."""
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import socket
import tempfile
import threading
import types
import zipfile

PUBLISH = Path(__file__).with_name('publish-release.sh')
NAMES = ['4789tv.apk', 'RELEASE_NOTES.md', 'SOURCE_PROVENANCE.json',
         'first-party-source.tar.gz', 'native-corresponding-source.tar.gz']
with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    for name in NAMES:
        (root / name).write_text('fixture')
    (root / 'SOURCE_PROVENANCE.json').write_text(json.dumps({'source_commit': 'a' * 40}))
    binary = root / 'bin'
    binary.mkdir()
    gh = binary / 'gh'
    gh.write_text('''#!/bin/sh
case "$*" in
  *object.sha*) echo bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb;;
  *object.type*) echo commit;;
  *git/trees*) echo '{"truncated":false,"tree":[]}';;
  *) echo 'Unexpected GitHub operation' >&2; exit 99;;
esac
''')
    gh.chmod(0o755)
    env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ['PATH'])
    def rejects(manifest, message):
        (root / 'SHA256SUMS.txt').write_text(manifest)
        result = subprocess.run(['bash', str(PUBLISH), 'v0.1.41', str(root)],
                                env=env, capture_output=True, text=True)
        assert result.returncode != 0 and message in result.stdout + result.stderr, result
    digest = hashlib.sha256(b'fixture').hexdigest()
    rejects(digest + '  4789tv.apk\n', 'every release input')
    rejects('0' * 64 + '  ../private\n', 'Invalid checksum')
    complete = ''.join(hashlib.sha256((root / name).read_bytes()).hexdigest() + '  ' + name + '\n' for name in NAMES)
    rejects(complete, 'differ from canonical tag')
    (root / 'DO_NOT_PUBLISH_PRIVATE_SOURCE.txt').write_text('Private staging')
    rejects(complete, 'Private source staging is blocked')
print('PASS release staging rejects incomplete hashes, path traversal, and mismatched source tags')

# Exercise the actual DEX guard with enough output to expose grep's early-exit SIGPIPE.
foundation = Path(__file__).with_name('check-android-foundation.sh').read_text()
guard_start = foundation.index('reject_dex_text() {')
guard_end = foundation.index('\n}', guard_start) + 2
dex_guard = foundation[guard_start:guard_end]
with tempfile.TemporaryDirectory(prefix='4789-dex-guard-') as tmp:
    root = Path(tmp)
    padding = b'harmless fixture string\n' * 65_536
    for label, data, rejected in (
        ('early', b'Install Player\n' + padding, True),
        ('late', padding + b'Install Player\n', True),
        ('absent', padding, False),
    ):
        apk = root / (label + '.apk')
        with zipfile.ZipFile(apk, 'w') as archive:
            archive.writestr('classes.dex', data)
        result = subprocess.run(
            ['bash', '-euo', 'pipefail', '-c', dex_guard + '\nreject_dex_text "$1" "$2"',
             'dex-guard-fixture', str(apk), 'Install Player'],
            capture_output=True, text=True, timeout=10,
        )
        assert (result.returncode != 0) == rejected, (label, result)
        if rejected:
            assert 'forbidden installer string' in result.stdout + result.stderr, result
print('PASS DEX guard rejects early and late installer strings without pipefail false negatives')

# A bounded receiver log may lose old lines during a test. Timestamp filtering keeps new events.
rotated = "2026-09-04T20:00:01.000-05:00 old\n2026-09-04T20:01:02.000-05:00 new\n"
result = subprocess.check_output(['awk', '-v', 'start=2026-09-04T20:01:00.000-05:00', '$1 > start'],
                                 input=rotated, text=True)
assert result == "2026-09-04T20:01:02.000-05:00 new\n"
print('PASS physical probe timestamp boundary survives log trimming')

# Fixture checks must reject receiver serials before invoking adb or writing test output.
with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    marker = root / 'adb-was-called'
    adb_fixture = root / 'adb'
    adb_fixture.write_text('#!/bin/sh\ntouch "' + str(marker) + '"\nexit 99\n')
    adb_fixture.chmod(0o700)
    environment = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'])
    for serial in ['receiver:5555', 'physical-device', '']:
        result = subprocess.run(['python3', str(Path(__file__).with_name('check-remote-ui.py')),
                                 '--serial', serial, '--disposable', '--output', str(root / 'output')],
                                env=environment, capture_output=True, text=True)
        assert result.returncode == 2 and 'no physical receiver commands were sent' in result.stderr
        assert not marker.exists() and not (root / 'output').exists()
print('PASS remote UI fixtures reject physical receivers before any adb action')

# GPU/compositor pacing must not be mislabeled as the app's main-thread work.
import importlib.util
timing_spec = importlib.util.spec_from_file_location('tv_performance', Path(__file__).with_name('tv-performance.py'))
timing = importlib.util.module_from_spec(timing_spec)
timing_spec.loader.exec_module(timing)
frame_fixture = '''---PROFILEDATA---
Flags,HandleInputStart,SyncQueued,FrameCompleted
0,10000000,12000000,30000000
0,20000000,23000000,40000000
1,30000000,90000000,95000000
0,0,10000000,20000000
0,60000000,50000000,70000000
0,incomplete
---PROFILEDATA---'''
assert timing.ui_thread_frame_times(frame_fixture) == [2.0, 3.0]
assert timing.percentile([2.0, 3.0], .95) == 3.0
assert timing.percentile([], .95) is None
print('PASS remote timing separates valid UI work from graphics pacing and rejects missing timestamps')

# Release inventory must cover every checked-in module, including profile generation inputs.
import re
EXPORT = Path(__file__).with_name('export-public-source.py')
settings = Path(__file__).resolve().parents[1] / 'settings.gradle.kts'
modules = re.findall(r'include\(":([^"\)]+)"\)', settings.read_text())
assert 'tvplay' not in modules
for module in modules:
    assert "'" + module + "'" in EXPORT.read_text(), module
    assert "'" + module + "'" in PUBLISH.read_text(), module
print('PASS source export/provenance includes all current Gradle module trees')

# Happy-path fixture must attach all checked content, including standalone release notes.
with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    package = root / 'package'
    package.mkdir()
    for name in NAMES:
        (package / name).write_text('fixture')
    module_names = re.findall(r'include\(":([^"\)]+)"\)', settings.read_text())
    paths = [f'4789TV/{name}' for name in module_names + ['gradle', 'licenses', 'build.gradle.kts',
             'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat',
             'compose_stability_config.conf']]
    paths += ['App/FourSevenEightNine/Resources/Fonts', 'docs/contract-samples']
    mapping = {name: 'c' * 40 for name in paths}
    (package / 'SOURCE_PROVENANCE.json').write_text(json.dumps({'source_commit': 'a' * 40,
                                                              'build_input_git_objects': mapping}))
    (package / 'SHA256SUMS.txt').write_text(''.join(
        hashlib.sha256((package / name).read_bytes()).hexdigest() + '  ' + name + '\n' for name in NAMES))
    tree = root / 'tree.json'
    tree.write_text(json.dumps({'truncated': False, 'tree': [{'path': p, 'sha': v} for p, v in mapping.items()]}))
    binary = root / 'bin'
    binary.mkdir()
    gh = binary / 'gh'
    gh.write_text("""#!/bin/sh
case "$*" in
  *object.sha*) echo bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb;;
  *object.type*) echo commit;;
  *git/trees*) cat "$RELEASE_TEST_TREE";;
  "release view"*) exit 1;;
  "release create"*) printf '%s\\n' "$@" > "$RELEASE_TEST_ARGS";;
  *) exit 99;;
esac
""")
    gh.chmod(0o755)
    sdk = root / 'sdk' / 'build-tools' / '36.0.0'
    sdk.mkdir(parents=True)
    for name, output in {
        'aapt2': "package: name='com.fourseveneightnine.tv' versionName='0.1.41'",
        'apksigner': 'Signer #1 certificate SHA-256 digest: fe937c685e7cd2c010af76a23c6ea4be0114b0e4146b62cc917dcd19b2d43205',
    }.items():
        executable = sdk / name
        executable.write_text("#!/bin/sh\ncat <<'FIXTURE'\n" + output + "\nFIXTURE\n")
        executable.chmod(0o755)
    capture = root / 'args.txt'
    env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ['PATH'],
               ANDROID_HOME=str(root / 'sdk'), RELEASE_TEST_TREE=str(tree), RELEASE_TEST_ARGS=str(capture))
    result = subprocess.run(['bash', str(PUBLISH), 'v0.1.41', str(package)], env=env,
                            capture_output=True, text=True)
    assert result.returncode == 0, result.stdout + result.stderr
    arguments = capture.read_text().splitlines()
    assert all(str(package / name) in arguments for name in NAMES), arguments
    capture.unlink()
    changed = dict(mapping, **{'4789TV/compose_stability_config.conf': 'd' * 40})
    tree.write_text(json.dumps({'truncated': False, 'tree': [{'path': p, 'sha': v} for p, v in changed.items()]}))
    result = subprocess.run(['bash', str(PUBLISH), 'v0.1.41', str(package)], env=env,
                            capture_output=True, text=True)
    assert result.returncode != 0 and 'Build inputs differ' in result.stdout + result.stderr, result
    assert not capture.exists(), 'mismatched compiler inputs must not reach release creation'
print('PASS release creation attaches every manifest-covered file including RELEASE_NOTES.md')
print('PASS canonical tag comparison rejects a changed Compose compiler input')

# Device operations are private mocks. The copied installer sees a dummy APK, so it never builds.
TV_ROOT = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='4789-support-', dir='/tmp') as tmp:
    root = Path(tmp)
    binary = root / 'bin'
    binary.mkdir()
    commands = {
        'adb': '''#!/bin/bash
printf '%s\\n' "$*" >> "$MOCK_ADB_LOG"
case "$*" in
  devices) printf 'List of devices attached\\n192.0.2.1:5555\\tdevice\\n';;
  *'shell true'*) exit "${MOCK_CONNECT_FAIL:-0}";;
  *'shell dumpsys package '*) printf '    pkgFlags=[ DEBUGGABLE HAS_CODE ]\\n';;
  *'shell pm list packages '*) printf '%s\\n' "package:com.fourseveneightnine.tv";;
  *'resolve-activity --brief '*) printf '%s\\n' 'fixture.home/.Home';;
  *'dumpsys activity activities'*) printf 'topResumedActivity=ActivityRecord{abcd u0 %s/.Main t21}\\n' "${MOCK_FOREGROUND:-fixture.home}";;
  *'dumpsys media_session'*) printf '  package=com.fourseveneightnine.tv\\n  state=PlaybackState {state=%s, position=0}\\n' "${MOCK_MEDIA:-STOPPED(1)}";;
  *'getprop ro.build.version.sdk'*) printf '28\\n';;
  *'getprop '*) printf 'FixtureBox\\n';;
  *'install -r'*)
    if [[ "$MOCK_INSTALL_FAIL" == 1 ]]; then printf 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]\\n'; exit 1; fi
    printf 'Success\\n';;
  *'shell am start '*)
    if [[ "$MOCK_LAUNCH_FAIL" != 0 ]]; then printf 'Error: fixture launch failure\\n'; exit "$MOCK_LAUNCH_FAIL"; fi
    if [[ "${MOCK_LAUNCH_ERROR:-0}" == 1 ]]; then printf 'Error: fixture launch failure\\n'; fi;;
  *'shell am force-stop '*|*'shell monkey '*|*'logcat -c'*|connect*) exit 0;;
  *) printf 'Unexpected mock adb call\\n' >&2; exit 99;;
esac
''',
        'curl': '''#!/bin/bash
printf '%s\\n' "$*" >> "$MOCK_CURL_LOG"
printf '%s\\n' "$MOCK_REPLY"
''',
        'ipconfig': '#!/bin/sh\nprintf "192.0.2.2\\n"\n',
        'ffmpeg': '#!/bin/sh\necho "Unexpected fixture generation" >&2\nexit 99\n',
    }
    for name, content in commands.items():
        executable = binary / name
        executable.write_text(content)
        executable.chmod(0o755)
    installer = root / 'install-tv.sh'
    installer.write_bytes((TV_ROOT / 'install-tv.sh').read_bytes())
    apk = root / 'app/build/outputs/apk/sideload/debug/app-sideload-debug.apk'
    apk.parent.mkdir(parents=True)
    apk.write_text('dummy APK; never installed')
    adb_log = root / 'adb.log'
    curl_log = root / 'curl.log'
    reply = json.dumps({'result': {'x4789': True, 'name': 'FixtureBox', 'httpPort': 8791,
                                  'engine': 'exo', 'hardwareVideoCodecs': ['h264'], 'audioCodecs': {}}})
    env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ['PATH'],
               MOCK_ADB_LOG=str(adb_log), MOCK_CURL_LOG=str(curl_log), MOCK_REPLY=reply,
               MOCK_INSTALL_FAIL='0', MOCK_LAUNCH_FAIL='0', MOCK_CONNECT_FAIL='0', MOCK_LAUNCH_ERROR='0')
    for override, expected in [({'MOCK_INSTALL_FAIL': '1'}, 1), ({'MOCK_LAUNCH_FAIL': '1'}, 1),
                               ({'MOCK_LAUNCH_ERROR': '1'}, 1), ({'MOCK_REPLY': '{"error":{"code":-1}}'}, 1),
                               ({'MOCK_CONNECT_FAIL': '1'}, 1), ({}, 0)]:
        adb_log.write_text('')
        curl_log.write_text('')
        result = subprocess.run(['bash', str(installer), '192.0.2.1'], env=dict(env, **override),
                                capture_output=True, text=True, timeout=10)
        assert result.returncode == expected, (override, result)
        assert ('phone should now find' in result.stdout) == (expected == 0), result
        if expected == 0:
            assert 'com.fourseveneightnine.tv/.client.ui.MainActivity' in adb_log.read_text()
        if override.get('MOCK_CONNECT_FAIL') == '1':
            assert 'Accept the prompt' in result.stderr and 'adb pair' in result.stderr, result
        if override.get('MOCK_INSTALL_FAIL') == '1':
            assert 'shell am ' not in adb_log.read_text(), adb_log.read_text()
        if override.get('MOCK_LAUNCH_FAIL') or override.get('MOCK_LAUNCH_ERROR'):
            assert not curl_log.read_text(), 'failed launch must not probe a previous receiver'
    verify = root / 'scripts/verify-receivers.sh'
    verify.parent.mkdir()
    verify.write_bytes((TV_ROOT / 'scripts/verify-receivers.sh').read_bytes())
    for arguments in ([], ['--no-install'], ['--host', '192.0.2.1']):
        adb_log.write_text('')
        curl_log.write_text('')
        result = subprocess.run(['bash', str(verify), *arguments], env=env,
                                capture_output=True, text=True, timeout=10)
        assert result.returncode == 64 and '--only' in result.stderr, result
        assert not adb_log.read_text() and not curl_log.read_text(), result
    adb_log.write_text('')
    result = subprocess.run(['bash', str(verify), '--probe-only', '--only', '192.0.2.1'], env=env,
                            capture_output=True, text=True, timeout=10)
    assert result.returncode == 0, result
    assert adb_log.read_text().splitlines() == ['devices'], adb_log.read_text()
    for state in ({'MOCK_FOREGROUND': 'com.fourseveneightnine.tv'}, {'MOCK_MEDIA': 'PAUSED(2)'}, {'MOCK_MEDIA': 'BUFFERING(6)'}):
        adb_log.write_text('')
        result = subprocess.run(['bash', str(installer), '192.0.2.1'], env=dict(env, **state), capture_output=True, text=True)
        assert result.returncode == 75 and 'install -r' not in adb_log.read_text()
print('PASS installer failure statuses, connection guidance, and read-only receiver probes')

# Old successful decoder events must not satisfy a new cold-open process check.
verifier_source = (TV_ROOT / 'scripts/verify-receivers.sh').read_text()
filter_start = verifier_source.index('diagnostics_for_run() {')
filter_end = verifier_source.index('\n}', filter_start) + 2
process_filter = verifier_source[filter_start:filter_end]
current_line = '2026-11-13T20:00:02Z | pid=42 | session=new | exo.firstFrameRendered\n'
private_log = ('2026-11-13T20:00:01Z | pid=142 | session=old | exo.audio.trackInit\n' +
               current_line +
               '2026-11-13T20:00:01Z | pid=42 | session=old | exo.audio.trackInit\n' +
               '2026-11-13T20:00:03Z | pid=142 | session=old | exo.firstFrameRendered\n')
result = subprocess.run(['bash', '-euo', 'pipefail', '-c',
                         process_filter + '\ndiagnostics_for_run "$1" "$2"', 'process-fixture', '42', 'session=old'],
                        input=private_log, capture_output=True, text=True, timeout=10)
assert result.returncode == 0 and result.stdout == current_line, result
result = subprocess.run(['bash', '-euo', 'pipefail', '-c',
                         process_filter + '\ndiagnostics_for_run "$1" "$2"', 'process-fixture', '', 'session=old'],
                        input=private_log, capture_output=True, text=True, timeout=10)
assert result.returncode != 0 and not result.stdout, result
print('PASS receiver verification requires a selected target and current-process decoder evidence')

# Execute definitions without main(), bytecode files, token writes, windows, or device commands.
installer_source = TV_ROOT / 'installer/serve.py'
installer_globals = {'__name__': 'installer_fixture', '__file__': str(installer_source)}
exec(compile(installer_source.read_text(), str(installer_source), 'exec'), installer_globals)
with tempfile.TemporaryDirectory(prefix='4789-installer-', dir='/tmp') as tmp:
    root = Path(tmp)
    relative_apk = Path(installer_globals['APK']).relative_to(installer_globals['TV_ROOT'])
    apk = root / 'app/build/outputs/apk/sideload/debug/app-sideload-debug.apk'
    apk.parent.mkdir(parents=True)
    apk.write_text('dummy APK')
    installer_globals.update(APK=str(root / relative_apk),
                             shutil=types.SimpleNamespace(which=lambda _: 'mock-adb'),
                             local_ipv4s=lambda: ['192.0.2.2'], port_open=lambda *_: True,
                             run=lambda *_args, **_kwargs: (0, 'device'),
                             adb=lambda *_args, **_kwargs: (0, '28' if 'ro.build.version.sdk' in _args else 'fixture'))
    for addresses, host in [(['192.0.2.2'], '192.0.2.1'),
                            (['192.168.0.10'], '192.168.1.20'),
                            ([], '192.168.1.20')]:
        installer_globals['local_ipv4s'] = lambda values=addresses: values
        log = io.BytesIO()
        assert installer_globals['preflight'](host, installer_globals['Log'](log)) is not None, log.getvalue()
        assert 'app-sideload-debug.apk ready' in log.getvalue().decode(), log.getvalue()
    installer_globals['port_open'] = lambda *_: False
    log = io.BytesIO()
    assert installer_globals['preflight']('192.168.1.20', installer_globals['Log'](log)) is None
    assert 'nothing is listening' in log.getvalue().decode(), log.getvalue()

Handler = installer_globals['Handler']
server = installer_globals['http'].server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
server.daemon_threads = True
thread = threading.Thread(target=server.serve_forever, daemon=True)
thread.start()
try:
    with socket.create_connection(server.server_address, timeout=3) as client:
        client.settimeout(3)
        reader = client.makefile('rb')
        for closing in (False, True):
            connection = b'Connection: close\r\n' if closing else b''
            client.sendall(b'POST /unsupported-fixture-action HTTP/1.1\r\nHost: 127.0.0.1\r\n' +
                           connection + b'Content-Length: 2\r\n\r\n{}')
            status = reader.readline()
            assert status.startswith(b'HTTP/1.1 200 '), status
            while reader.readline() != b'\r\n':
                pass
            while True:
                count = int(reader.readline().strip(), 16)
                if count == 0:
                    assert reader.readline() == b'\r\n'
                    break
                assert len(reader.read(count)) == count
                assert reader.read(2) == b'\r\n'
        reader.close()
finally:
    server.shutdown()
    server.server_close()
    thread.join(timeout=3)
print('PASS flavored installer APK preflight and consecutive chunked responses on one loopback connection')

# Native discovery must preserve every selected case and reject incomplete or duplicate lists.
remote_spec = importlib.util.spec_from_file_location('remote_ui', Path(__file__).with_name('check-remote-ui.py'))
remote_ui = importlib.util.module_from_spec(remote_spec)
remote_spec.loader.exec_module(remote_ui)
discovery = """INSTRUMENTATION_STATUS: class=fixture.Check
INSTRUMENTATION_STATUS: test=first
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=fixture.Check
INSTRUMENTATION_STATUS: test=first
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_STATUS: class=fixture.Check
INSTRUMENTATION_STATUS: test=second
INSTRUMENTATION_STATUS_CODE: 1
OK (2 tests)
"""
assert remote_ui.listed_tests(discovery) == ['fixture.Check#first', 'fixture.Check#second']
for invalid in ('OK (0 tests)', discovery.replace('OK (2 tests)', 'INSTRUMENTATION_FAILED'),
                discovery.replace('test=second', 'test=first')):
    try:
        remote_ui.listed_tests(invalid)
    except RuntimeError:
        pass
    else:
        raise AssertionError('Incomplete or duplicate native discovery was accepted')
with tempfile.TemporaryDirectory(prefix='4789-instrument-timeout-') as tmp:
    import sys
    log = Path(tmp) / 'partial.txt'
    stopped = []
    try:
        remote_ui.run_logged([sys.executable, '-u', '-c',
            'import time; print("test started", flush=True); time.sleep(10)'],
            log, lambda: stopped.append(True), timeout=0.2)
    except subprocess.TimeoutExpired:
        pass
    else:
        raise AssertionError('Expected timeout')
    assert stopped == [True]
    assert 'test started' in log.read_text() and 'HOST_TIMEOUT' in log.read_text()
print('PASS native discovery, retained timeout output, and owned instrumentation retirement')

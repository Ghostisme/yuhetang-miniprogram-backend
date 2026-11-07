import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import threading
import unittest


SPEC = importlib.util.spec_from_file_location('callback_diagnostic', Path(__file__).with_name('diagnose-wechat-callback.py'))
diagnostic = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(diagnostic)


def entry(nonce):
    return ('192.0.2.10 - - [07/Nov/2026:21:33:19 +0800] '
            '"POST /api/wechat/customer-service?signature=PRIVATE_SIGNATURE&nonce=' + nonce +
            ' HTTP/1.1" 200 7 "-" "test-client"\n')


class CallbackLogTests(unittest.TestCase):
    def test_container_reader_is_used_instead_of_an_unrelated_host_path(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertTrue(diagnostic.recent_posts('container-probe', Path('missing-host-path'), wait_seconds=0,
                                                     reader=lambda: entry('container-probe')))
        self.assertIn('Current public POST found in this log: True', output.getvalue())
        self.assertNotIn('PRIVATE_SIGNATURE', output.getvalue())

    def test_missing_container_log_is_not_reported_as_verified(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertFalse(diagnostic.recent_posts('current-probe', wait_seconds=0, reader=lambda: None))
        self.assertIn('could not be read', output.getvalue())

    def test_matching_probe_is_confirmed_without_exposing_request_parameters(self):
        with tempfile.TemporaryDirectory() as folder:
            log = Path(folder) / 'access.log'
            log.write_text(entry('current-probe'), encoding='utf-8')
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertTrue(diagnostic.recent_posts('current-probe', log, wait_seconds=0))
            self.assertIn('Current public POST found in this log: True', output.getvalue())
            self.assertIn('21:33:19', output.getvalue())
            for hidden in ('PRIVATE_SIGNATURE', 'current-probe', '192.0.2.10'):
                self.assertNotIn(hidden, output.getvalue())

    def test_historical_only_log_is_not_used_as_proof_that_wechat_sent_nothing(self):
        with tempfile.TemporaryDirectory() as folder:
            log = Path(folder) / 'access.log'
            log.write_text(entry('old-probe'), encoding='utf-8')
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertFalse(diagnostic.recent_posts('current-probe', log, wait_seconds=0))
            self.assertIn('Current public POST found in this log: False', output.getvalue())
            self.assertIn('do NOT establish that WeChat sent no message', output.getvalue())

    def test_buffered_log_is_rechecked_before_reporting_it_unconfirmed(self):
        with tempfile.TemporaryDirectory() as folder:
            log = Path(folder) / 'access.log'
            log.write_text('', encoding='utf-8')
            timer = threading.Timer(0.05, lambda: log.write_text(entry('flushed-probe'), encoding='utf-8'))
            timer.start()
            try:
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertTrue(diagnostic.recent_posts('flushed-probe', log, wait_seconds=2))
            finally:
                timer.join()


if __name__ == '__main__':
    unittest.main()

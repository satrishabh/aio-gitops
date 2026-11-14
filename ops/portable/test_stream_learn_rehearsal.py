import contextlib
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('stream', Path(__file__).with_name('stream-learn-rehearsal.py'))
stream = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stream)


class RehearsalSafetyTests(unittest.TestCase):
    def invoke(self, responses, execute=False):
        argv = ['stream', 'sarvaikam', '--kubeconfig', 'isolated.kubeconfig']
        if execute:
            argv.append('--execute')
        with patch('sys.argv', argv), patch.object(stream.subprocess, 'check_output', side_effect=responses), \
                patch.object(stream.subprocess, 'run') as mutation, \
                patch.object(stream.subprocess, 'Popen') as transfer, \
                contextlib.redirect_stdout(io.StringIO()) as output:
            stream.main()
            return output.getvalue(), mutation, transfer

    def test_preflight_does_not_create_or_transfer(self):
        output, mutation, transfer = self.invoke(['9000000', 'plpgsql\nuuid-ossp', ''])
        self.assertIn('"executed": false', output)
        mutation.assert_not_called()
        transfer.assert_not_called()

    def test_existing_destination_refused_before_mutation(self):
        with self.assertRaisesRegex(RuntimeError, 'refusing to overwrite'):
            self.invoke(['9000000', 'plpgsql', '1'], execute=True)

    def test_incompatible_extensions_refused(self):
        with self.assertRaisesRegex(RuntimeError, 'Review source extensions'):
            self.invoke(['9000000', 'plpgsql\ncloudsql_specific'], execute=True)

    def test_oversized_database_refused(self):
        with self.assertRaisesRegex(RuntimeError, 'exceeds 512Mi'):
            self.invoke([str(513 * 1024 * 1024), 'plpgsql'], execute=True)


if __name__ == '__main__':
    unittest.main()

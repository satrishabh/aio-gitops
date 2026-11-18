import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('stream_schema', Path(__file__).with_name('stream-schema-rehearsal.py'))
stream = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stream)


class SchemaRehearsalGuards(unittest.TestCase):
    def test_existing_database_is_never_overwritten(self):
        with patch('sys.argv', ['script', '--kubeconfig', 'test', '--execute']), \
                patch.object(stream, 'query', return_value='1'), \
                patch.object(stream, 'audit') as audit, \
                patch.object(stream.subprocess, 'run') as mutation:
            with self.assertRaisesRegex(RuntimeError, 'refusing to overwrite'):
                stream.main()
            audit.assert_not_called()
            mutation.assert_not_called()

    def test_custom_functions_require_review(self):
        metadata = dict(schemas=['public'], userFunctions=1, largeObjects=0, extensions=['plpgsql'], bytes=100)
        with patch.object(stream, 'query', return_value=json.dumps(metadata)):
            with self.assertRaisesRegex(RuntimeError, 'compatibility review'):
                stream.audit('sarvaikam')

    def test_extra_schema_requires_review(self):
        metadata = dict(schemas=['public', 'custom'], userFunctions=0, largeObjects=0, extensions=['plpgsql'], bytes=100)
        with patch.object(stream, 'query', return_value=json.dumps(metadata)):
            with self.assertRaisesRegex(RuntimeError, 'compatibility review'):
                stream.audit('sarvaikam')

    def test_fingerprint_query_quotes_catalog_identifiers(self):
        sql = stream.fingerprint_sql('sarvaikam', ['table"quote'])
        self.assertIn('"sarvaikam"."table""quote"', sql)
        self.assertIn('ORDER BY md5(row_to_json(t)::text)', sql)


if __name__ == '__main__':
    unittest.main()

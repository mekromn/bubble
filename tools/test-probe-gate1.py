"""Gate 1 JSON schema regression tests. Fixtures are synthetic, not device results."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('comparison', ROOT/'tools/compare-renderer-probe.py')
c = importlib.util.module_from_spec(spec)
spec.loader.exec_module(c)


class SchemaRegression(unittest.TestCase):
    def row(self, offset=0, present=True):
        return [1_000_000+offset, 2_000_000+offset, 0, 3_000_000+offset,
                5_000_000+offset, 4_000_000+offset if present else -1, 2**61+1]

    def test_legacy_seven(self):
        rows, audit = c.decode_native_samples({'samples':[self.row()]})
        self.assertEqual(audit['decoded_rows'], 1)
        self.assertEqual(rows[0]['hardwareBufferId'], 2**61+1)

    def test_named_eleven(self):
        rows, audit = c.decode_native_samples({'sampleColumns':c.V2_COLUMNS,
                                             'samples':[self.row()+[100,200,6000000,2]]})
        self.assertFalse(audit['errors'])
        self.assertEqual(rows[0]['releaseCallbackNs'],6000000)

    def test_legacy_eleven_without_header(self):
        rows, audit = c.decode_native_samples({'samples':[self.row()+[100,200,6000000,2]]})
        self.assertEqual(rows[0]['outstandingDepth'],2)
        self.assertEqual(audit['schema_source'],'explicit_legacy_length_fallback')

    def test_reordered_and_extended_header(self):
        values=dict(zip(c.V2_COLUMNS,self.row()+[100,200,6000000,2]))
        values['submissionSequence']=77
        names=list(reversed(values))
        rows,audit=c.decode_native_samples({'sampleColumns':names,'samples':[[values[k] for k in names]]})
        self.assertEqual(rows[0]['presentFenceSignalNs'],4000000)
        self.assertEqual(rows[0]['submissionSequence'],77)
        self.assertFalse(audit['errors'])

    def test_corruption_reported(self):
        rows,audit=c.decode_native_samples({'sampleColumns':c.V2_COLUMNS,'samples':[self.row(),None]})
        self.assertFalse(rows)
        self.assertEqual(audit['rejected_rows'],2)
        self.assertTrue(audit['errors'])

    def test_duplicate_header_rejected(self):
        rows,audit=c.decode_native_samples({'sampleColumns':c.LEGACY_COLUMNS+['acquireNs'],
                                           'samples':[self.row()+[1]]})
        self.assertFalse(rows)
        self.assertIn('invalid_or_incomplete_sample_columns',audit['errors'])

    def test_missing_field_not_mislabelled_zero(self):
        names=c.LEGACY_COLUMNS[:-1]
        rows,audit=c.decode_native_samples({'sampleColumns':names,'samples':[self.row()[:-1]]})
        self.assertFalse(rows)
        self.assertTrue(audit['errors'])

    def test_ambiguous_headerless_width(self):
        rows,audit=c.decode_native_samples({'samples':[self.row()+[1]]})
        self.assertFalse(rows)
        self.assertEqual(audit['rejected_rows'],1)

    def test_missing_fence_and_reused_allocation(self):
        rows,audit=c.decode_native_samples({'samples':[self.row(),self.row(10_000_000),self.row(20_000_000,False)]})
        self.assertEqual(len(rows),3)
        self.assertEqual(len({r['hardwareBufferId'] for r in rows}),1)
        result=c.analyze([('a.json',{'spec':{'renderer':'relay'},'native':{'samples':[self.row(),self.row(10_000_000),self.row(20_000_000,False)]}})])['trials'][0]
        self.assertEqual(result['present_observations'],2)
        self.assertEqual(result['missing_present_observations'],1)
        self.assertAlmostEqual(result['present_coverage_pct'],200/3)
        self.assertEqual(result['observed_acquire_to_present_p50_ms'],3)

    def test_null_fence_stays_missing(self):
        row=self.row();row[5]=None
        rows,audit=c.decode_native_samples({'samples':[row]})
        self.assertEqual(len(rows),1)
        self.assertIsNone(rows[0]['presentFenceSignalNs'])

    def test_invalid_required_value(self):
        row=self.row();row[0]='bad'
        rows,audit=c.decode_native_samples({'samples':[row]})
        self.assertFalse(rows)
        self.assertEqual(audit['rejected_rows'],1)

    def test_excludes_partial_duplicate_documents(self):
        with tempfile.TemporaryDirectory() as d:
            for name in ['trial.json','trial.partial.json','trial.pending.json','plan.json']:
                (Path(d)/name).write_text(json.dumps({'spec':{}}))
            self.assertEqual(len(list(c.load(Path(d)))),1)


if __name__=='__main__':unittest.main()

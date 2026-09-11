import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('comparison',Path(__file__).with_name('compare-renderer-probe.py'))
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
class ComparisonTests(unittest.TestCase):
    def test_empty_unknown(self): self.assertIsNone(c.summarize([])['hz'])
    def test_interval_rate(self): self.assertAlmostEqual(c.summarize([1000/120]*30)['hz'],120)
    def test_present_fence_dedup(self): self.assertAlmostEqual(c.timestamp_rate([1,1,10000001,10000001,20000001])['hz'],100)
    def test_tail(self): self.assertEqual(c.summarize([8]*18+[50])['p95_ms'],50)
    def test_failure_not_ranked(self):
        out=c.analyze([('case.json',{'spec':{'variant':'failed'},'status':'WATCHDOG_TIMEOUT_OR_NATIVE_FREEZE'})])
        self.assertFalse(out['trials'][0]['valid_timing_observation'])
        self.assertIsNone(out['trials'][0]['raf_hz'])
if __name__=='__main__':unittest.main()

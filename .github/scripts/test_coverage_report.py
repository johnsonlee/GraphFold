import copy
import json
from pathlib import Path
import tempfile
import unittest

from coverage_report import CoverageError, MODULES, PLATFORMS, collect, metric_text, passes, table, validate


def report(platform=PLATFORMS[0]):
    return {"schema": 1, "platform": platform, "modules": {name: {"error": None,
        "LINE": {"missed": 0, "covered": 10}, "BRANCH": {"missed": 0, "covered": 4}} for name in MODULES}}


class CoverageReportTest(unittest.TestCase):
    def test_module_inventory_matches_production_projects(self):
        self.assertEqual({"engine", "gfc", "plugin/gradle", "plugin/maven"}, set(MODULES))

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for module in MODULES:
            self.write(module)

    def write(self, module, line_missed="0", covered="10", extra=""):
        path = self.root / module / "build/reports/jacoco/test/jacocoTestReport.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f'<report><counter type="LINE" missed="{line_missed}" covered="{covered}"/>'
            f'<counter type="BRANCH" missed="0" covered="4"/>{extra}</report>')
        return path

    def test_every_module_requires_exact_zero_missed(self):
        self.assertTrue(passes(collect(self.root, PLATFORMS[0])))
        self.write("engine", "1", "1000000")
        result = collect(self.root, PLATFORMS[0])
        self.assertFalse(passes(result))
        self.assertNotIn("100%", metric_text(result["modules"]["engine"], "LINE"))

    def test_branch_miss_and_zero_totals_do_not_pass(self):
        for metric in ("LINE", "BRANCH"):
            for counter in ({"missed": 0, "covered": 0}, {"missed": 1, "covered": 99}):
                with self.subTest(metric=metric, counter=counter):
                    result = report()
                    result["modules"]["engine"][metric] = counter
                    self.assertFalse(passes(result))
                    self.assertNotIn("100%", metric_text(result["modules"]["engine"], metric))

    def test_missing_and_malformed_reports_are_explicit(self):
        path = self.write("engine")
        path.unlink()
        result = collect(self.root, PLATFORMS[0])
        self.assertEqual(CoverageError.MISSING_REPORT, result["modules"]["engine"]["error"])
        for xml in ("broken", "<report/>", "<other/>", '<report><counter type="LINE" missed="-1" covered="2"/></report>'):
            path.write_text(xml)
            result = collect(self.root, PLATFORMS[0])
            self.assertFalse(passes(result))
            self.assertEqual(CoverageError.INVALID_REPORT, result["modules"]["engine"]["error"])

    def test_noninteger_or_duplicate_xml_counts_are_rejected(self):
        for missed in ("-1", "+1", "1.2", "true", " 0", "1000000001", "١"):
            self.write("engine", missed)
            self.assertEqual(CoverageError.INVALID_REPORT, collect(self.root, PLATFORMS[0])["modules"]["engine"]["error"])
        self.write("engine", extra='<counter type="LINE" missed="0" covered="1"/>')
        self.assertFalse(passes(collect(self.root, PLATFORMS[0])))

    def test_json_schema_rejects_missing_modules_extra_fields_and_invalid_counts(self):
        changes = [lambda d: d["modules"].pop("engine"), lambda d: d.update(pr=1),
            lambda d: d.update(schema=True), lambda d: d.update(platform=PLATFORMS[1]),
            lambda d: d["modules"]["engine"].update(error="injected markdown")]
        for value in (True, -1, "0", 0.5, 1000000001):
            changes.append(lambda d, value=value: d["modules"]["engine"]["LINE"].update(missed=value))
        for change in changes:
            document = report()
            change(document)
            with self.assertRaises(ValueError):
                validate(document, PLATFORMS[0])
        self.assertEqual(report(), validate(json.loads(json.dumps(report())), PLATFORMS[0]))

    def test_error_enums_preserve_json_wire_values_and_validate_back_to_enums(self):
        document = report()
        document["modules"]["engine"] = {"error": CoverageError.MISSING_REPORT, "LINE": None, "BRANCH": None}
        wire = json.loads(json.dumps(document))
        self.assertEqual("MISSING_REPORT", wire["modules"]["engine"]["error"])
        parsed = validate(wire, PLATFORMS[0])
        self.assertIs(CoverageError.MISSING_REPORT, parsed["modules"]["engine"]["error"])
        self.assertEqual("Missing Report", metric_text(parsed["modules"]["engine"], "LINE"))

    def test_partial_platform_is_reported_missing(self):
        text = table({PLATFORMS[0]: report()})
        self.assertEqual(len(MODULES) * 2, text.count("Missing platform report"))
        for module in MODULES:
            self.assertIn(f"`{module}`", text)

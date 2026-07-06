"""Regenerates smart_classic.xlsx, the fixture for SmartXlsxParserTest.

Run from the repo root:  python app/src/test/resources/fixtures/make_fixture.py
Requires: openpyxl

NOTE: SmartXlsxParser resolves worksheet rels with RELATIVE targets
("worksheets/sheetN.xml"), the format real Excel / Google Sheets exports use.
openpyxl writes ABSOLUTE targets ("/xl/worksheets/sheetN.xml"), so we post-process
the saved workbook to relative targets — making the fixture representative of the
coach-sheet inputs the parser is actually built to handle.
"""
import os
import re
import shutil
import zipfile
import openpyxl

wb = openpyxl.Workbook()

# Program Info sheet — drives program name + week count.
info = wb.active
info.title = "Program Info"
info["A1"] = "Program Name"; info["C1"] = "Test Strength Program"
info["A2"] = "Weeks";        info["C2"] = "4"

# W1D1 sheet — classic Wild Odds template detection path.
d = wb.create_sheet("W1D1")
d["A1"] = "Push Day"
d["A2"] = "Muscle Groups: Chest, Shoulders"
d["A3"] = "Exercise"; d["B3"] = "Sets"; d["C3"] = "Reps"; d["D3"] = "Weight"; d["E3"] = "Notes"
d["A4"] = "Bench Press";    d["B4"] = "3"; d["C4"] = "8-10"; d["D4"] = "60"; d["E4"] = "warmup first"
d["A5"] = "Overhead Press"; d["B5"] = "3"; d["C5"] = "10";   d["D5"] = "40"

out = os.path.join(os.path.dirname(__file__), "smart_classic.xlsx")
tmp = out + ".tmp"
wb.save(tmp)

# Rewrite workbook rels targets to the relative form real Excel emits.
src = zipfile.ZipFile(tmp, "r")
dst = zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED)
for item in src.namelist():
    data = src.read(item)
    if item == "xl/_rels/workbook.xml.rels":
        text = data.decode("utf-8")
        text = re.sub(r'Target="/xl/', 'Target="', text)
        data = text.encode("utf-8")
    dst.writestr(item, data)
src.close()
dst.close()
os.remove(tmp)
print("wrote", out)

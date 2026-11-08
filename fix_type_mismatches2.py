import os
import re

files = [
    'app/src/main/java/com/wirelessalien/android/moviedb/activity/CsvImportActivity.kt',
    'app/src/main/java/com/wirelessalien/android/moviedb/activity/MoviebaseImportActivity.kt',
    'app/src/main/java/com/wirelessalien/android/moviedb/activity/TraktImportActivity.kt'
]

for filepath in files:
    with open(filepath, 'r') as f:
        content = f.read()

    # Cast Any to String when calling putExtra
    content = content.replace('putExtra("report_json_path", jsonPath)', 'putExtra("report_json_path", jsonPath as String)')
    content = content.replace('putExtra("report_csv_path", csvPath)', 'putExtra("report_csv_path", csvPath as String)')

    with open(filepath, 'w') as f:
        f.write(content)

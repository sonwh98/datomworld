# Run inside the pinned Python 2 image. Metadata derives from Apache-2.0 Autobahn.
import json
from autobahntestsuite.case import Cases, CaseCategories
import autobahntestsuite
rows = []
for index, case in enumerate(Cases, 1):
    cid = case.__name__[4:].replace('_', '.')
    rows.append({'id': cid, 'index': index, 'category': CaseCategories[cid.split('.')[0]],
                 'description': case.DESCRIPTION, 'expectation': case.EXPECTATION})
print(json.dumps({'version': autobahntestsuite.__version__, 'cases': rows}, sort_keys=True))

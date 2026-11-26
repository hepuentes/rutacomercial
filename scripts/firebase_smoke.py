"""Run with firebase emulators:exec --only auth,firestore --project demo-rutacomercial."""
import json
import os
from urllib.error import HTTPError
from urllib.request import Request, urlopen

project = "demo-rutacomercial"
auth = "http://" + os.environ["FIREBASE_AUTH_EMULATOR_HOST"]
db = "http://" + os.environ["FIRESTORE_EMULATOR_HOST"] + "/v1/projects/" + project + "/databases/(default)/documents"

def call(method, url, body=None, token=None, expect=200):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = Request(url, data=json.dumps(body).encode() if body is not None else None,
                      headers=headers, method=method)
    try:
        with urlopen(request, timeout=15) as response:
            status, data = response.status, response.read()
    except HTTPError as error:
        status, data = error.code, error.read()
    assert status == expect, (method, url, status, expect, data[:300])
    return json.loads(data)

def account():
    user = call("POST", auth + "/identitytoolkit.googleapis.com/v1/accounts:signUp?key=demo",
                {"returnSecureToken": True})
    return user["localId"], user["idToken"]

def fields(**values):
    return {"fields": {k: {"stringValue": v} for k, v in values.items()}}

owner, owner_token = account()
worker, worker_token = account()
_, outside = account()
task = db + "/tasks/demo-task"
report = task + "/reports/demo-session"
call("PATCH", task, fields(owner=owner, worker=worker), owner_token)
call("GET", task, token=worker_token)
call("GET", task, token=outside, expect=403)
call("PATCH", task, fields(owner=worker, worker=worker), worker_token, 403)
call("PATCH", report, fields(state="ACTIVE"), worker_token)
call("PATCH", report, fields(state="FINISHED"), worker_token)
received = call("GET", report, token=owner_token)
assert received["fields"]["state"]["stringValue"] == "FINISHED"
call("GET", report, token=outside, expect=403)
call("PATCH", report, fields(state="PAUSED"), outside, 403)
call("PATCH", report + "/parts/0", fields(body="part"), worker_token)
call("GET", report + "/parts/0", token=owner_token)
call("GET", report + "/parts/0", token=outside, expect=403)
print("Asignación, avance, cierre y aislamiento entre dispositivos verificados.")

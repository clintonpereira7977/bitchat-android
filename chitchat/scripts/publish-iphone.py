"""Publish only the independent unsigned iPhone build. Never modify the Android APK."""
import base64, hashlib, json, os, subprocess
from pathlib import Path
from urllib.parse import quote
repo=os.environ['GITHUB_REPOSITORY']
branch='codex/chitchat-download'
def api(method, endpoint, body=None, optional=False):
    command=['gh','api','--method',method,endpoint]
    if body is not None: command+=['--input','-']
    r=subprocess.run(command,input=json.dumps(body) if body is not None else None,text=True,capture_output=True)
    if r.returncode:
        if optional and '404' in r.stderr: return None
        raise RuntimeError('iPhone publication failed: '+r.stderr)
    return json.loads(r.stdout)
ref=api('GET',f'repos/{repo}/git/ref/heads/{branch}',optional=True)
if ref is None: api('POST',f'repos/{repo}/git/refs',{'ref':'refs/heads/'+branch,'sha':os.environ['GITHUB_SHA']})
info={'app':'ChitChat','version':'0.2.0','protocol':'ChitChat/v2','minimumIOS':'16.0','architecture':'arm64','sourceCommit':os.environ['GITHUB_SHA'],'workflowRun':os.environ['GITHUB_RUN_ID'],'deviceBuild':'success','swiftChecks':'passed','signed':False,'installation':'Sign with your own Apple Account using SideStore, Sideloadly or Xcode. This unsigned IPA cannot be installed directly.','physicalIPhoneTested':False,'sha256':hashlib.sha256(Path('dist/ChitChat-iPhone-unsigned.ipa').read_bytes()).hexdigest()}
Path('dist/BUILDINFO-iPhone.json').write_text(json.dumps(info,indent=2)+'\n')
for name in ['ChitChat-iPhone-unsigned.ipa','SHA256SUMS-iPhone.txt','BUILDINFO-iPhone.json','INSTALL-IPHONE-WINDOWS.md']:
    p=Path('dist')/name; endpoint=f'repos/{repo}/contents/downloads/'+quote(name,safe='')
    previous=api('GET',endpoint+'?ref='+quote(branch,safe=''),optional=True)
    body={'message':'Publish unsigned ChitChat iPhone build and Windows installation guide','branch':branch,'content':base64.b64encode(p.read_bytes()).decode()}
    if previous: body['sha']=previous['sha']
    api('PUT',endpoint,body); print('Published downloads/'+name,flush=True)

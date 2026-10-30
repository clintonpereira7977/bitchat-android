"""Publish tested Online beta without replacing the working Nearby 0.2 aliases."""
import base64,hashlib,json,os,subprocess
from pathlib import Path
from urllib.parse import quote

repo=os.environ['GITHUB_REPOSITORY'];branch='codex/chitchat-download'
def api(method,endpoint,body=None,optional=False):
    result=subprocess.run(['gh','api','--method',method,endpoint]+(['--input','-'] if body is not None else []),input=json.dumps(body) if body is not None else None,text=True,capture_output=True)
    if result.returncode:
        if optional and '404' in result.stderr:return None
        raise RuntimeError('Download publication failed: '+result.stderr)
    return json.loads(result.stdout)

apk=Path('dist/ChitChat-0.3-beta2.apk')
live=json.loads(Path('dist/LIVE-SERVICE.json').read_text()) if Path('dist/LIVE-SERVICE.json').is_file() else {'reachable':False}
info={'app':'ChitChat','version':'0.3.0-beta2','package':'app.chitchat','minimumAndroid':'8.0','sourceCommit':os.environ['GITHUB_SHA'],'workflowRun':os.environ['GITHUB_RUN_ID'],'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'checks':'Native Online, notifications and Bluetooth checks passed','physicalOnlinePhonesVerified':False,'physicalNotificationsVerified':False,'permanentReleaseSigning':False,'liveService':live,'backendNeedsLatestSource':live.get('pushPayload')!='data-only-v1','transport':['Bluetooth Nearby/v2','Internet Online/v1 (HPKE + P256 signatures)']}
Path('dist/BUILDINFO-0.3-beta2.json').write_text(json.dumps(info,indent=2)+'\n')
Path('dist/INSTALL-ONLINE-0.3-beta2.txt').write_text(Path('docs/ONLINE-ANDROID.md').read_text())
for path in [apk,Path('dist/SHA256SUMS-0.3-beta2.txt'),Path('dist/BUILDINFO-0.3-beta2.json'),Path('dist/INSTALL-ONLINE-0.3-beta2.txt'),*sorted(Path('dist/online-ui').glob('*.png'))]:
    target='downloads/'+('beta2-'+path.name if path.suffix=='.png' else path.name);endpoint=f'repos/{repo}/contents/{quote(target,safe="/")}'
    old=api('GET',endpoint+'?ref='+quote(branch,safe=''),optional=True)
    body={'branch':branch,'message':'Publish validated ChitChat Online Android beta','content':base64.b64encode(path.read_bytes()).decode()}
    if old:body['sha']=old['sha']
    api('PUT',endpoint,body);print('Published',target,flush=True)

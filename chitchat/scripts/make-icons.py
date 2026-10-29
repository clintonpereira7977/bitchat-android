"""Regenerate original monochrome app artwork (requires Pillow)."""
from pathlib import Path
from PIL import Image, ImageDraw
import json

root = Path(__file__).resolve().parents[1]
assets = root/'ios/ChitChat/Assets.xcassets'
assets.mkdir(exist_ok=True)
(assets/'Contents.json').write_text(json.dumps({'info':{'author':'xcode','version':1}},indent=2))
black = assets/'LaunchBlack.colorset'; black.mkdir(exist_ok=True)
(black/'Contents.json').write_text(json.dumps({'colors':[{'idiom':'universal','color':{'color-space':'srgb','components':{'red':'0.000','green':'0.000','blue':'0.000','alpha':'1.000'}}}],'info':{'author':'xcode','version':1}},indent=2))
icon = assets/'AppIcon.appiconset'; icon.mkdir(exist_ok=True)
im=Image.new('RGB',(2048,2048),'black'); d=ImageDraw.Draw(im)
d.line([(540,570),(1508,570),(1508,1250),(1010,1250),(700,1510),(700,1250),(540,1250),(540,570)],fill='white',width=85,joint='curve')
for x in [760,1024,1288]: d.ellipse((x-53,857,x+53,963),fill='white')
im.resize((1024,1024),Image.Resampling.LANCZOS).save(icon/'icon-1024.png')
images=[{'idiom':'universal','platform':'ios','size':'1024x1024','filename':'icon-1024.png'}]
(icon/'Contents.json').write_text(json.dumps({'images':images,'info':{'author':'xcode','version':1}},indent=2))
print('Wrote original app icon and black launch color')

"""Regenerate the checked-in Xcode project without third-party tools."""
from pathlib import Path
import hashlib

root = Path(__file__).resolve().parents[1] / 'ios'
project = root / 'ChitChat.xcodeproj'
project.mkdir(exist_ok=True)
def uid(name): return hashlib.sha256(name.encode()).hexdigest()[:24].upper()
objects = []
def add(name, value): objects.append(f'\t\t{uid(name)} = {{ {value} }};')
files = ['ChitChatApp.swift', 'ContentView.swift', 'ChatModel.swift', 'SessionCrypto.swift', 'ConsentGate.swift']
for file in files:
    add('file:'+file, f'isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = {file}; sourceTree = "<group>";')
    add('build:'+file, f'isa = PBXBuildFile; fileRef = {uid("file:"+file)};')
add('plist', 'isa = PBXFileReference; lastKnownFileType = text.plist.xml; path = Info.plist; sourceTree = "<group>";')
add('assets', 'isa = PBXFileReference; lastKnownFileType = folder.assetcatalog; path = Assets.xcassets; sourceTree = "<group>";')
add('assets-build', f'isa = PBXBuildFile; fileRef = {uid("assets")};')
add('product', 'isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = ChitChat.app; sourceTree = BUILT_PRODUCTS_DIR;')
refs = ', '.join(uid('file:'+f) for f in files)
add('appgroup', f'isa = PBXGroup; children = ({refs}, {uid("plist")}, {uid("assets")},); path = ChitChat; sourceTree = "<group>";')
add('products', f'isa = PBXGroup; children = ({uid("product")},); name = Products; sourceTree = "<group>";')
add('rootgroup', f'isa = PBXGroup; children = ({uid("appgroup")}, {uid("products")},); sourceTree = "<group>";')
builds = ', '.join(uid('build:'+f) for f in files)
add('sources', f'isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = ({builds},); runOnlyForDeploymentPostprocessing = 0;')
add('frameworks', 'isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;')
add('resources', f'isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = ({uid("assets-build")},); runOnlyForDeploymentPostprocessing = 0;')
common = 'CLANG_ENABLE_MODULES = YES; IPHONEOS_DEPLOYMENT_TARGET = 16.0; SDKROOT = iphoneos; SWIFT_VERSION = 5.0;'
target = 'ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon; CODE_SIGN_STYLE = Automatic; CURRENT_PROJECT_VERSION = 2; GENERATE_INFOPLIST_FILE = NO; INFOPLIST_FILE = ChitChat/Info.plist; MARKETING_VERSION = 0.2.0; PRODUCT_BUNDLE_IDENTIFIER = app.chitchat.local; PRODUCT_NAME = "$(TARGET_NAME)"; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator"; TARGETED_DEVICE_FAMILY = 1; SWIFT_EMIT_LOC_STRINGS = YES;'
for scope in ['project', 'target']:
    for config in ['Debug', 'Release']:
        extra = 'SWIFT_OPTIMIZATION_LEVEL = "-Onone"; SWIFT_ACTIVE_COMPILATION_CONDITIONS = DEBUG;' if config == 'Debug' else 'SWIFT_COMPILATION_MODE = wholemodule; SWIFT_OPTIMIZATION_LEVEL = "-O";'
        add(f'{scope}-{config}', f'isa = XCBuildConfiguration; buildSettings = {{ {common} {target if scope == "target" else ""} {extra} }}; name = {config};')
    add(scope+'-configs', f'isa = XCConfigurationList; buildConfigurations = ({uid(scope+"-Debug")}, {uid(scope+"-Release")},); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;')
add('target', f'isa = PBXNativeTarget; buildConfigurationList = {uid("target-configs")}; buildPhases = ({uid("sources")}, {uid("frameworks")}, {uid("resources")},); buildRules = (); dependencies = (); name = ChitChat; productName = ChitChat; productReference = {uid("product")}; productType = "com.apple.product-type.application";')
add('project', f'isa = PBXProject; attributes = {{ BuildIndependentTargetsInParallel = YES; LastSwiftUpdateCheck = 1600; LastUpgradeCheck = 1600; }}; buildConfigurationList = {uid("project-configs")}; compatibilityVersion = "Xcode 14.0"; developmentRegion = en; hasScannedForEncodings = 0; knownRegions = (en, Base,); mainGroup = {uid("rootgroup")}; productRefGroup = {uid("products")}; projectDirPath = ""; projectRoot = ""; targets = ({uid("target")},);')
(project/'project.pbxproj').write_text('// !$*UTF8*$!\n{\n\tarchiveVersion = 1;\n\tclasses = {};\n\tobjectVersion = 56;\n\tobjects = {\n'+'\n'.join(objects)+'\n\t};\n\trootObject = '+uid('project')+';\n}\n')
scheme = project/'xcshareddata/xcschemes/ChitChat.xcscheme'
scheme.parent.mkdir(parents=True,exist_ok=True)
ref = f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{uid("target")}" BuildableName="ChitChat.app" BlueprintName="ChitChat" ReferencedContainer="container:ChitChat.xcodeproj"/>'
scheme.write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1600" version="1.3">
<BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{ref}</BuildActionEntry></BuildActionEntries></BuildAction>
<TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"/>
<LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{ref}</BuildableProductRunnable></LaunchAction>
<ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">{ref}</BuildableProductRunnable></ProfileAction>
<AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>''')
print('Wrote', project)

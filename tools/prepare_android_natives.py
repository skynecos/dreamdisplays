"""Recreate the separate Android bundle from the verified legacy mobile artifact."""
import hashlib,sys,zipfile
from pathlib import Path
source=Path(sys.argv[1])
assert hashlib.sha256(source.read_bytes()).hexdigest()=='01f4a9a9fbab1fad686d2d62b8210d7dd28686bca3551537e158f15b100f6fc9'
root=Path(__file__).resolve().parents[1]
bundle=root/'native/build/android-bundle/dreamdisplays-natives/linux-aarch64'
expected={
 'libdreamdisplays_lav.so':'7e644e354f0935ce46a3e0882f8b47e765bbcc1e76ca162bf8f30ad09ac0bbfa',
 'libdreamdisplays_native.so':'82c123e930dde8e42bc2897b20c9f6ad9e864d2de7ebff5cab8b848089f930ab'
}
with zipfile.ZipFile(source) as z:
 for name,digest in expected.items():
  data=z.read('dreamdisplays-natives/linux-aarch64/'+name)
  assert hashlib.sha256(data).hexdigest()==digest
  bundle.mkdir(parents=True,exist_ok=True);(bundle/name).write_bytes(data)
license=bundle/'licenses/ffmpeg-license.txt'
license.parent.mkdir(parents=True,exist_ok=True)
license.write_text('Android ARM64 DreamDisplays native bridges, LGPL-3.0.\nFFmpeg libraries are supplied separately by Kirazium Launcher; they are not bundled here.\nSources: https://github.com/skynecos/dreamdisplays (mobilefix1)\nAndroid bridge payload reproduced byte-for-byte from mobilefix1; see KIRAZIUM-ANDROID.md.\n',encoding='utf-8')
print('Verified Android-only native bundle:',bundle)

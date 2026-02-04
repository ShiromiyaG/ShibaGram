# TDLib Setup for ShibaGram

This project uses **TDLib (Telegram Database Library)** for Telegram API access.
Unlike TDLight, TDLib requires you to download or build the native library yourself.

## Option 1: Download Prebuilt Binary (Recommended)

### Windows x64

1. Download TDLib from one of these sources:
   - [nicegram-tdlib-releases](https://github.com/nicegram/nicegram-tdlib-releases/releases) (if available)
   - [AyuGram TDLib builds](https://github.com/AyuGram/AyuGramDesktop/releases) (extract tdjni.dll from the package)
   - Build it yourself (see Option 2)

2. Place `tdjni.dll` in one of these locations:
   - `libs/tdjni.dll` (in the project root)
   - Project root: `tdjni.dll`
   - Or any folder in your system PATH

### Required Files
- `tdjni.dll` - The main TDLib JNI binding
- Make sure you have Visual C++ Redistributable 2019 or newer installed

## Option 2: Build TDLib Yourself

### Prerequisites (Windows)
- Visual Studio 2019 or 2022 with C++ workload
- CMake 3.16+
- Git
- OpenSSL (or use vcpkg)
- zlib (or use vcpkg)

### Using vcpkg (Recommended)

```powershell
# Clone vcpkg
git clone https://github.com/microsoft/vcpkg
cd vcpkg
.\bootstrap-vcpkg.bat

# Install dependencies
.\vcpkg install openssl:x64-windows zlib:x64-windows

# Clone TDLib
cd ..
git clone https://github.com/tdlib/td
cd td

# Build TDLib
mkdir build
cd build
cmake -A x64 -DCMAKE_TOOLCHAIN_FILE=[vcpkg root]/scripts/buildsystems/vcpkg.cmake ..
cmake --build . --config Release

# The tdjni.dll will be in the build/Release folder
```

### JNI Binding

After building TDLib, you need to create the JNI binding. The native functions expected are:

```c
JNIEXPORT jint JNICALL Java_com_shirou_shibagram_tdlib_TdLib_createClient(JNIEnv *, jclass);
JNIEXPORT void JNICALL Java_com_shirou_shibagram_tdlib_TdLib_send(JNIEnv *, jclass, jint, jstring);
JNIEXPORT jstring JNICALL Java_com_shirou_shibagram_tdlib_TdLib_receive(JNIEnv *, jclass, jdouble);
JNIEXPORT jstring JNICALL Java_com_shirou_shibagram_tdlib_TdLib_execute(JNIEnv *, jclass, jstring);
JNIEXPORT void JNICALL Java_com_shirou_shibagram_tdlib_TdLib_destroyClient(JNIEnv *, jclass, jint);
```

## Troubleshooting

### "Can't find dependent libraries"
Install Microsoft Visual C++ Redistributable:
- https://aka.ms/vs/17/release/vc_redist.x64.exe

### "UnsatisfiedLinkError"
1. Make sure tdjni.dll is in one of the expected locations
2. Check that you have a 64-bit JDK
3. Verify Visual C++ Redistributable is installed

### API Credentials
Don't forget to set your Telegram API credentials in `TelegramClientService.kt`:
```kotlin
private const val API_ID = YOUR_API_ID
private const val API_HASH = "YOUR_API_HASH"
```

Get credentials at: https://my.telegram.org

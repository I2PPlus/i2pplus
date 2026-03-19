Tanuki Java Service Wrapper 3.6.4 - Windows x64 binaries
=======================================================

BUILDING FROM LINUX (mingw-w64)
-------------------------------
Requirements:
  sudo apt install mingw-w64 binutils-mingw-w64-x86-64 gcc-mingw-w64-x86-64 git

Build:
  bash scripts/build-wrapper-windows.sh /path/to/wrapper_3.6.4_src --arch x64

Options:
  --arch x64|win32    Target architecture (default: x64)
  --install-dir PATH  Installation directory
  --no-dll            Skip DLL build (build exe only)
  --clean             Clean build artifacts

Output:
  I2Psvc.exe   - Windows service wrapper executable (555KB)
  wrapper.dll  - JNI library for Java integration (56KB)

BUILDING FROM WINDOWS (Visual Studio)
------------------------------------
Copy Makefile-windows-x86-64.nmake to src\c.

Copy the itoopie icon to src\c\wrapper.ico.

Configure your environment per the apache-ant instructions (i.e., set ANT_HOME
and JAVA_HOME). Then in the wrapper source directory, run build64.bat.

Compiled in VS2010SP1 after creating %USERPROFILE%\.ant.properties with:
vcvars.bat="C:\\Program Files (x86)\\Microsoft Visual Studio 10.0\\VC\\bin\\amd64\\vcvars64.bat".

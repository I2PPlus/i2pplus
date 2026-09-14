/* Vendor copy of the Windows (MSVC/x64) jni_md.h, kept in-tree because the
 * local JDK's jni_md.h is Linux-specific: JNICALL is __stdcall on Windows and
 * long is 32-bit on Win64, so the Linux header would build an ABI-mismatched
 * wrapper.dll. Content mirrors the OpenJDK win32 jni_md.h.
 */

#ifndef _JAVASOFT_JNI_MD_H_
#define _JAVASOFT_JNI_MD_H_

#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL __stdcall

typedef long jint;
typedef long long jlong;
typedef signed char jbyte;

#endif /* _JAVASOFT_JNI_MD_H_ */
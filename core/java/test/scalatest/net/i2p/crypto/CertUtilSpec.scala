package net.i2p.crypto

import java.io.File
import java.nio.file.Files
import java.security.cert.X509Certificate

import org.scalatest.FunSpec
import org.scalatest.Matchers


class CertUtilSpec extends FunSpec with Matchers {

  describe("CertUtil") {
    val certFile = Files.createTempFile("crt", ".crt").toFile
    certFile.deleteOnExit
    val rv = SelfSignedGenerator.generate("test@mail.i2p", null, null, null, null, null, 365, SigType.ECDSA_SHA256_P256)
    val cert = rv(2).asInstanceOf[X509Certificate]
    CertUtil.saveCert(cert, certFile)

    it("should save and read back a certificate") {
      val loaded: X509Certificate = CertUtil.loadCert(certFile)
      assert(loaded.getPublicKey == cert.getPublicKey)
      assert(loaded.getSerialNumber == cert.getSerialNumber)
    }

    it("should extract the subject CN") {
      assert(CertUtil.getSubjectValue(cert, "CN") === "test@mail.i2p")
    }

    it("should tell if a fresh certificate is not revoked") {
      assert(CertUtil.isRevoked(cert) === false)
    }

    it("should load a key from a certificate file") {
      val key = CertUtil.loadKey(certFile)
      assert(key == cert.getPublicKey)
    }

    it("should throw on a non-certificate file") {
      val bogus = Files.createTempFile("bogus", ".crt").toFile
      bogus.deleteOnExit
      Files.write(bogus.toPath, "not a certificate".getBytes)
      intercept[java.security.GeneralSecurityException] {
        CertUtil.loadCert(bogus)
      }
    }
  }
}

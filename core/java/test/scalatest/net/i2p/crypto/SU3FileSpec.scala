package net.i2p.crypto

import java.io.File
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.util.Properties

import org.scalatest.FunSpec
import org.scalatest.Matchers


class SU3FileSpec extends FunSpec with Matchers {

  private def setCert(su3: SU3File, certFile: File): Unit = {
    val setCert = classOf[SU3File].getDeclaredMethod("setPublicKeyCertificate", classOf[File])
    setCert.setAccessible(true)
    setCert.invoke(su3, certFile)
  }

  private def makeFixture(): (File, File) = {
    val dir = Files.createTempDirectory("su3test").toFile
    dir.deleteOnExit
    val contentFile = new File(dir, "content.xml")
    Files.write(contentFile.toPath, "<news>test</news>".getBytes(StandardCharsets.UTF_8))
    val certFile = new File(dir, "key.crt")
    val su3File = new File(dir, "news.su3")
    su3File.deleteOnExit
    val rv = SelfSignedGenerator.generate("test@mail.i2p", null, null, null, null, null, 365, SigType.ECDSA_SHA256_P256)
    CertUtil.saveCert(rv(2).asInstanceOf[java.security.cert.X509Certificate], certFile)
    new SU3File(su3File).write(contentFile, SU3File.TYPE_XML, SU3File.CONTENT_NEWS, "1.0.0", "test@mail.i2p",
                               rv(1).asInstanceOf[java.security.PrivateKey], SigType.ECDSA_SHA256_P256)
    (su3File, certFile)
  }

  private def makeContext(baseDir: File): net.i2p.I2PAppContext = {
    val props = new Properties
    props.setProperty("i2p.dir.base", baseDir.getAbsolutePath)
    new net.i2p.I2PAppContext(props)
  }

  describe("SU3File") {
    it("should verify a self-signed file") {
      val (su3File, certFile) = makeFixture()
      val su3 = new SU3File(su3File)
      setCert(su3, certFile)
      su3.verify() should be (true)
    }

    it("should verify after a header getter ran first") {
      val (su3File, certFile) = makeFixture()
      val su3 = new SU3File(su3File)
      setCert(su3, certFile)
      su3.getSignerString() should be ("test@mail.i2p")
      su3.verify() should be (true)
    }

    it("should reject a file with modified content") {
      val (su3File, certFile) = makeFixture()
      val data = Files.readAllBytes(su3File.toPath)
      data(data.length - 5) = (data(data.length - 5) ^ 0xFF).toByte
      Files.write(su3File.toPath, data)
      val su3 = new SU3File(su3File)
      setCert(su3, certFile)
      su3.verify() should be (false)
    }

    it("should return header fields after a getter ran first") {
      val (su3File, certFile) = makeFixture()
      val su3 = new SU3File(su3File)
      setCert(su3, certFile)
      su3.getSignerString() should be ("test@mail.i2p")
      su3.getVersionString() should be ("1.0.0")
      su3.getContentType() should be (SU3File.CONTENT_NEWS)
      su3.getFileType() should be (SU3File.TYPE_XML)
      su3.getSigType() should be (SigType.ECDSA_SHA256_P256)
      su3.verify() should be (true)
    }

    it("should extract the content via the DirKeyRing (production) path") {
      val dir = Files.createTempDirectory("su3keyring").toFile
      dir.deleteOnExit
      val certDir = new File(new File(new File(dir, "certificates"), "news"), "test_at_mail.i2p.crt")
      certDir.getParentFile.mkdirs
      val contentFile = new File(dir, "content.xml")
      val content = "<news>keyring</news>".getBytes(StandardCharsets.UTF_8)
      Files.write(contentFile.toPath, content)
      val su3File = new File(dir, "news.su3")
      su3File.deleteOnExit
      val rv = SelfSignedGenerator.generate("test@mail.i2p", null, null, null, null, null, 365, SigType.ECDSA_SHA256_P256)
      CertUtil.saveCert(rv(2).asInstanceOf[java.security.cert.X509Certificate], certDir)
      new SU3File(su3File).write(contentFile, SU3File.TYPE_XML, SU3File.CONTENT_NEWS, "1.0.0", "test@mail.i2p",
                                 rv(1).asInstanceOf[java.security.PrivateKey], SigType.ECDSA_SHA256_P256)
      val su3 = new SU3File(makeContext(dir), su3File)
      val outFile = new File(dir, "extracted.xml")
      su3.verifyAndMigrate(outFile) should be (true)
      Files.readAllBytes(outFile.toPath) should be (content)
    }

    it("should reject an unknown signer via the DirKeyRing path") {
      val dir = Files.createTempDirectory("su3keyring").toFile
      dir.deleteOnExit
      val contentFile = new File(dir, "content.xml")
      Files.write(contentFile.toPath, "<news>test</news>".getBytes(StandardCharsets.UTF_8))
      val su3File = new File(dir, "news.su3")
      su3File.deleteOnExit
      val rv = SelfSignedGenerator.generate("someone@mail.i2p", null, null, null, null, null, 365, SigType.ECDSA_SHA256_P256)
      new SU3File(su3File).write(contentFile, SU3File.TYPE_XML, SU3File.CONTENT_NEWS, "1.0.0", "someone@mail.i2p",
                                 rv(1).asInstanceOf[java.security.PrivateKey], SigType.ECDSA_SHA256_P256)
      val su3 = new SU3File(makeContext(dir), su3File)
      intercept[java.io.IOException] {
        su3.verify()
      }
    }
  }
}

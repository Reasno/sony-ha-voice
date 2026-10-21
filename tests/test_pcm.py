"""Exercise the actual Java PCM codec and endpoint detector on known signals."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
JDK = pathlib.Path("/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home/bin")

class PcmTests(unittest.TestCase):
    def test_pcm_and_endpoint(self):
        source = r"""
package dev.guxi.sonyhavoice;
import java.util.Arrays;
public class PcmCheck {
  static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
  public static void main(String[] args) {
    byte[] pcm = {0, (byte)128, (byte)255, 127};
    check(Math.abs(Pcm.rms(pcm) - 32767.5) < 0.01, "signed little endian");
    check(Arrays.equals(Pcm.frame(201, pcm),
      new byte[]{(byte)201, 0, (byte)128, (byte)255, 127}), "prefix preserves PCM");
    check(Arrays.equals(Pcm.frame(201, new byte[0]), new byte[]{(byte)201}), "EOF");
    try { Pcm.frame(256, pcm); throw new AssertionError("invalid handler accepted"); }
    catch (IllegalArgumentException expected) {}
    try { Pcm.frame(1, new byte[3]); throw new AssertionError("odd PCM accepted"); }
    catch (IllegalArgumentException expected) {}
    Pcm.EndDetector silence = new Pcm.EndDetector();
    for (int i = 0; i < 399; i++) check(!silence.add(8, 20), "silence ended early");
    check(silence.add(8, 20), "no speech timeout");
    Pcm.EndDetector phrase = new Pcm.EndDetector();
    for (int i = 0; i < 30; i++) check(!phrase.add(8, 20), "calibration");
    for (int i = 0; i < 60; i++) check(!phrase.add(75, 20), "quiet phrase truncated");
    for (int i = 0; i < 64; i++) check(!phrase.add(8, 20), "pause ended early");
    check(phrase.add(8, 20), "phrase endpoint");
    Pcm.EndDetector constant = new Pcm.EndDetector();
    for (int i = 0; i < 30; i++) constant.add(8, 20);
    for (int i = 30; i < 999; i++) check(!constant.add(3000, 20), "continuous voice");
    check(constant.add(3000, 20), "maximum recording timeout");
  }
}
"""
        with tempfile.TemporaryDirectory() as directory:
            work = pathlib.Path(directory)
            check = work / "PcmCheck.java"
            check.write_text(source)
            subprocess.run([str(JDK / "javac"), "--release", "8", "-Xlint:-options",
                "-d", directory, str(ROOT / "app/src/main/java/dev/guxi/sonyhavoice/Pcm.java"),
                str(check)], check=True)
            subprocess.run([str(JDK / "java"), "-cp", directory,
                "dev.guxi.sonyhavoice.PcmCheck"], check=True)

if __name__ == "__main__":
    unittest.main()

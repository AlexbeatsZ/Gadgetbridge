#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Compare a synthetic vector with the inspected AccountSDK Java method bodies.

Requires the user's existing local jadx output, never downloads/repackages SDK
code. The extracted reference and class files are written only to --work-dir.
"""
import argparse
from pathlib import Path
import subprocess


def method(source, marker):
    start = source.index(marker)
    brace = source.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--sources', required=True, type=Path)
    p.add_argument('--java-home', required=True, type=Path)
    p.add_argument('--work-dir', required=True, type=Path)
    a = p.parse_args()
    coder = (a.sources/'com/xiaomi/accountsdk/utils/Coder.java').read_text()
    secure = (a.sources/'com/xiaomi/accountsdk/request/SecureRequest.java').read_text()
    sign = method(coder, 'public static String generateSignature(')
    # jadx emitted a raw TreeMap in an enhanced for loop; restore its erased
    # generic type so javac accepts the unchanged iteration semantics.
    sign = sign.replace('new TreeMap(map)', 'new TreeMap<String, String>(map)')
    encrypt = method(secure, 'public static Map<String, String> encryptParams(String str, String str2, Map<String, String> map, String str3, CryptCoder cryptCoder)')
    reference = r'''
import java.util.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
public class Reference {
    static class TextUtils { static boolean isEmpty(String s) {return s==null||s.isEmpty();} }
    static class Typography { static char amp='&'; }
    static class Uri {
        final java.net.URI uri;
        Uri(String s) {uri=java.net.URI.create(s);}
        static Uri parse(String s) {return new Uri(s);}
        String getEncodedPath() {return uri.getRawPath();}
    }
    static String hash4SHA1(String s) {
        try {return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA1").digest(s.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e) {throw new IllegalStateException(e);}
    }
    static class RegionManagerImpl { static String LOCALE_REPORTED_SERVER_SEPARATOR="_"; }
    static class MiTsmConstants { static String KEY_SIGNATURE="signature"; }
    static class SignatureCoder {
        static String generateSignature(String m,String u,Map<String,String> p,String s) {
            return Reference.generateSignature(m,u,p,s);
        }
    }
    static class CipherException extends Exception {CipherException(String s){super(s);} }
    interface CryptCoder {String encrypt(String value) throws CipherException;}
    static class AESCoder implements CryptCoder {
        final byte[] key;
        AESCoder(String s) {key=Base64.getDecoder().decode(s);}
        public String encrypt(String value) throws CipherException {
            try {
                Cipher c=Cipher.getInstance("AES/CBC/PKCS5Padding");
                c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec("0102030405060708".getBytes(StandardCharsets.UTF_8)));
                return Base64.getEncoder().encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)));
            } catch(GeneralSecurityException e) {throw new CipherException("synthetic reference failed");}
        }
    }
    @SIGN@
    @ENCRYPT@
    public static void main(String[] args) throws Exception {
        String security="AAECAwQFBgcICQoLDA0ODw==";
        Map<String,String> values=new HashMap<>();
        values.put("userId","test-user"); values.put("deviceModel","Xiaomi Watch S4");
        values.put("note","复旦/测试 & +");values.put("_nonce","public-test");
        Map<String,String> result=encryptParams("POST","https://tsmapi.pay.xiaomi.com/api/login/acl/checkServiceAvailable",values,security,new AESCoder(security));
        for(Map.Entry<String,String> e:new TreeMap<>(result).entrySet()) System.out.println(e.getKey()+"\t"+e.getValue());
    }
}
'''.replace('@SIGN@', sign).replace('@ENCRYPT@', encrypt)
    a.work_dir.mkdir(parents=True, exist_ok=True)
    file = a.work_dir/'Reference.java'
    file.write_text(reference)
    subprocess.run([str(a.java_home/'bin/javac'), '-encoding', 'UTF-8', str(file)], check=True)
    output = subprocess.check_output([str(a.java_home/'bin/java'), '-cp', str(a.work_dir), 'Reference'], text=True)
    observed = dict(line.split('\t', 1) for line in output.splitlines())
    from test_protocol import GOLDEN
    if observed != GOLDEN:
        raise SystemExit('AccountSDK reference vector mismatch')
    print('PASS: extracted AccountSDK Java method bodies match all 5 synthetic wire fields')


if __name__ == '__main__':
    main()

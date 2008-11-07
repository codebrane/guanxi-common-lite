//: "The contents of this file are subject to the Mozilla Public License
//: Version 1.1 (the "License"); you may not use this file except in
//: compliance with the License. You may obtain a copy of the License at
//: http://www.mozilla.org/MPL/
//:
//: Software distributed under the License is distributed on an "AS IS"
//: basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
//: License for the specific language governing rights and limitations
//: under the License.
//:
//: The Original Code is Guanxi (http://www.guanxi.uhi.ac.uk).
//:
//: The Initial Developer of the Original Code is Alistair Young alistair@codebrane.com
//: All Rights Reserved.
//:

package org.guanxi.common.trust;

import org.guanxi.xal.saml_2_0.metadata.IDPSSODescriptorType;
import org.guanxi.xal.saml_2_0.metadata.KeyDescriptorType;
import org.guanxi.xal.saml_2_0.metadata.EntityDescriptorType;
import org.guanxi.xal.saml_2_0.metadata.SSODescriptorType;
import org.guanxi.xal.w3.xmldsig.X509DataType;
import org.guanxi.xal.w3.xmldsig.KeyInfoType;
import org.guanxi.xal.w3.xmldsig.SignatureType;
import org.guanxi.xal.saml_1_0.protocol.ResponseDocument;
import org.guanxi.common.GuanxiException;
import org.apache.log4j.Logger;
import org.apache.xml.security.signature.XMLSignature;
import org.apache.xml.security.keys.KeyInfo;
import org.apache.xml.security.exceptions.XMLSecurityException;
import org.w3c.dom.Element;

import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.cert.CertificateException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Vector;
import java.util.Arrays;

/**
 * Shibboleth and SAML trust functionality
 *
 * @author alistair
 */
public class TrustUtils {
  /** Our logger */
  private static final Logger logger = Logger.getLogger(TrustUtils.class.getName());

  /**
   * Performs trust validation via X509 certificates embedded in metadata. The trust is in the context
   * of a secure connection to an AA.
   *
   * @param saml2Metadata The metadata for the SP
   * @param clientCerts The SP's client certificates from the secure connection
   * @return true if validation succeeds otherwise false
   * @throws GuanxiException if an error occurs
   */
  public static boolean validateClientCert(EntityDescriptorType saml2Metadata, X509Certificate[] clientCerts) throws GuanxiException {
    X509Certificate[] x509sFromMetadata = getX509CertsFromSPMetadata(saml2Metadata);

    // Try validation via direct X509 in metadata
    for (X509Certificate clientCert : clientCerts) {
      for (X509Certificate x509FromMetadata : x509sFromMetadata) {
        if (x509FromMetadata.equals(clientCert)) {
          return true;
        }
      }
    }

    // Try validation via PKIX path validation
    String[] keyNames = getKeyNamesFromSPMetadata(saml2Metadata);
    for (String keyName : keyNames) {
      for (X509Certificate clientCert : clientCerts) {
        if (compareX509SubjectWithKeyName(clientCert, keyName)) {
          return true;
        }
      }
    }

    return false;
  }

  /**
   * Performs trust validation via X509 certificates embedded in metadata. The trust is in the context
   * of a SAML Response coming from an IdP.
   *
   * @param samlResponse The SAML Response from an IdP containing an AuthenticationStatement
   * @param saml2Metadata The metadata for the IdP
   * @return true if validation succeeds otherwise false
   * @throws GuanxiException if an error occurs
   */
  public static boolean validateWithEmbeddedCert(ResponseDocument samlResponse, EntityDescriptorType saml2Metadata) throws GuanxiException {
    X509Certificate[] x509sFromMetadata = getX509CertsFromIdPMetadata(saml2Metadata);
    X509Certificate x509CertFromSig = getX509CertFromSignature(samlResponse);

    for (X509Certificate x509FromMetadata : x509sFromMetadata) {
      if (x509CertFromSig.equals(x509FromMetadata)) {
        return true;
      }
    }

    /* Direct signature validation via RSAKeyValue
     * @todo implement this
     */

    return false;
  }

  /**
   * Performs PKIX path validation
   *
   * @param samlResponse The SAML Response from an IdP containing an AuthenticationStatement
   * @param saml2Metadata The metadata for the IdP
   * @param caCerts The list of CA root certs as trust anchors
   * @return true if validation succeeds otherwise false
   * @throws GuanxiException if an error occurs
   */
  public static boolean validatePKIX(ResponseDocument samlResponse, EntityDescriptorType saml2Metadata,
                                     Vector<X509Certificate> caCerts) throws GuanxiException {
    /* PKIX Path Validation
     * quickie summary:
     * - Match X509 in SAML Response signature to KeyName in IdP metadata
     * - Match issuer of X509 in SAML Response to one of the X509s in shibmeta:keyauthority
     *   in global metadata
     */
    X509Certificate x509CertFromSig = getX509CertFromSignature(samlResponse);

    // First find a match between the X509 in the signature and a KeyName in the metadata...
    if (matchCertToKeyName(x509CertFromSig, saml2Metadata)) {
      // ...then follow the chain from the X509 in the signature back to a supported CA in the metadata
      if (validateCertPath(x509CertFromSig, caCerts)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Retrieves the X509Certificate from a digital signature
   *
   * @param samlResponse The SAML Response containing the signature
   * @return X509Certificate from the signature
   * @throws GuanxiException if an error occurs
   */
  public static X509Certificate getX509CertFromSignature(ResponseDocument samlResponse) throws GuanxiException {
    try {
      KeyInfoType keyInfo = samlResponse.getResponse().getSignature().getKeyInfo();
      byte[] x509CertBytes = keyInfo.getX509DataArray(0).getX509CertificateArray(0);
      CertificateFactory certFactory = CertificateFactory.getInstance("x.509");
      ByteArrayInputStream certByteStream = new ByteArrayInputStream(x509CertBytes);
      X509Certificate cert = (X509Certificate)certFactory.generateCertificate(certByteStream);
      certByteStream.close();
      return cert;
    }
    catch(CertificateException ce) {
      logger.error("Error obtaining certificate factory", ce);
      throw new GuanxiException(ce);
    }
    catch(IOException ioe) {
      logger.error("Error closing certificate byte stream", ioe);
      throw new GuanxiException(ioe);
    }
  }

  /**
   * Tries to match an X509 certificate subject to a KeyName in metadata
   *
   * @param x509 The X509 to match with a KeyName
   * @param saml2Metadata The metadata which contains the KeyName
   * @return true if a match was made, otherwise false
   */
  public static boolean matchCertToKeyName(X509Certificate x509, EntityDescriptorType saml2Metadata) {
    IDPSSODescriptorType[] idpSSOs = saml2Metadata.getIDPSSODescriptorArray();

    // EntityDescriptor/IDPSSODescriptor
    for (IDPSSODescriptorType idpSSO : idpSSOs) {
      // EntityDescriptor/IDPSSODescriptor/KeyDescriptor
      KeyDescriptorType[] keyDescriptors = idpSSO.getKeyDescriptorArray();

      for (KeyDescriptorType keyDescriptor : keyDescriptors) {
        // EntityDescriptor/IDPSSODescriptor/KeyDescriptor/KeyInfo
        if (keyDescriptor.getKeyInfo() != null) {
          // EntityDescriptor/IDPSSODescriptor/KeyDescriptor/KeyInfo/KeyName
          if (keyDescriptor.getKeyInfo().getKeyNameArray() != null) {
            String[] keyNames = keyDescriptor.getKeyInfo().getKeyNameArray();

            for (String keyName : keyNames) {
              String metadataKeyName = new String(keyName.getBytes());

              // Do the hard work of comparison
              if (compareX509SubjectWithKeyName(x509, metadataKeyName)) {
                return true;
              }
            }
          }
        }
      }
    }

    return false;
  }

  /**
   * Compares an X509 subject to a KeyName string using various techniques.
   * We can hit a problem with certs when the IdP's providerId is, e.g. urn:uni:ac:uk:idp
   * but it's cert DN is CN=urn:uni:ac:uk:idp, OU=Unknown, O=Unknown, L=Unknown, ST=Unknown, C=Unknown
   * We didn't see this in the beginning as the certs generated by BouncyCastle in the IdP don't have
   * the extra OU etc. Most commandline tools do put the extra OU in if you don't specify them.
   *
   * @param x509 The X509 to use
   * @param keyName The KeyName string to use
   * @return if they match, otherwise false
   */
  public static boolean compareX509SubjectWithKeyName(X509Certificate x509, String keyName) {
    logger.debug("subject DN : " + x509.getSubjectDN().getName() + ", KeyName : " + keyName);

    // Try the full DN
    logger.debug("trying DN : " + x509.getSubjectDN().getName() + " KeyName : " + keyName);
    if (x509.getSubjectDN().getName().equals(keyName)) {
      logger.debug("matched DN");
      return true;
    }

    // Try the CN
    String cn = x509.getSubjectDN().getName().split(",")[0].split("=")[1];
    logger.debug("trying CN : " + cn + " KeyName : " + keyName);
    if (cn.equals(keyName)) {
      logger.debug("matched CN");
      return true;
    }

    return false;
  }

  /**
   * Validates a certificate path starting with the mystery cert and working
   * back to a trust anchor, using the CA certs in the trust engine.
   *
   * @param x509ToVerify the mystery cert, should we trust it?
   * @param caCerts the list of CA root certs to trust
   * @return true if we trust the cert, otherwise false
   */
  public static boolean validateCertPath(X509Certificate x509ToVerify, Vector<X509Certificate> caCerts) {
    for (X509Certificate caX509 : caCerts) {
      if (caX509.getSubjectDN().getName().equals(x509ToVerify.getIssuerDN().getName())) {
        return true;
      }
    }

    return false;
  }

  /**
   * Verifies the digital signature on a SAML Response
   *
   * @param samlResponse The SAML Response document containing the signature
   * @return true if the signature verifies otherwise false
   * @throws GuanxiException if an error occurs
   */
  public static boolean verifySignature(ResponseDocument samlResponse) throws GuanxiException {
    try {
      // Get the signature on the SAML response...
      SignatureType sig = samlResponse.getResponse().getSignature();
      if (sig != null) {
        // ...and verify it
        XMLSignature signature = new XMLSignature((Element)sig.getDomNode(),"");
        KeyInfo keyInfo = signature.getKeyInfo();
        if (keyInfo != null) {
          if(keyInfo.containsX509Data()) {
            X509Certificate cert = signature.getKeyInfo().getX509Certificate();
            if(cert != null) {
              return signature.checkSignatureValue(cert);
            }
          }
        }
      }
    }
    catch(XMLSecurityException xse) {
      logger.error("Error translating signature from DOM to XMLSignature", xse);
      throw new GuanxiException(xse);
    }

    return false;
  }

  /**
   * Extracts X509 certificates from a SAML2 IdP EntityDescriptor
   *
   * @param saml2Metadata The SAML2 metadata which may contain the certificates
   * @return array of X509Certificate objects created from the metadata
   * @throws GuanxiException if an error occurs
   */
  public static X509Certificate[] getX509CertsFromIdPMetadata(EntityDescriptorType saml2Metadata) throws GuanxiException {
    return getX509CertsFromMetadata(saml2Metadata.getIDPSSODescriptorArray());
  }

  /**
   * Extracts X509 certificates from a SAML2 SP EntityDescriptor
   *
   * @param saml2Metadata The SAML2 metadata which may contain the certificates
   * @return array of X509Certificate objects created from the metadata
   * @throws GuanxiException if an error occurs
   */
  public static X509Certificate[] getX509CertsFromSPMetadata(EntityDescriptorType saml2Metadata) throws GuanxiException {
    return getX509CertsFromMetadata(saml2Metadata.getSPSSODescriptorArray());
  }

  /**
   * Extracts X509 certificates from a SAML2 EntityDescriptor
   *
   * @param entityDescriptors The SAML2 metadata which may contain the certificates. This can either be
   * an IDPSSODescriptor or an SPSSODescriptor
   * @return array of X509Certificate objects created from the metadata
   * @throws GuanxiException if an error occurs
   */
  public static X509Certificate[] getX509CertsFromMetadata(SSODescriptorType[] entityDescriptors) throws GuanxiException {
    if (entityDescriptors == null) {
      return null;
    }

    Vector<X509Certificate> x509Certs = new Vector<X509Certificate>();

      for (SSODescriptorType entityDescriptor : entityDescriptors) {
        KeyDescriptorType[] keys = entityDescriptor.getKeyDescriptorArray();

        // SSODescriptor/KeyDescriptor
        for (KeyDescriptorType key : keys) {
          if (key.getKeyInfo() != null) {
            // SSODescriptor/KeyDescriptor/KeyInfo
            if (key.getKeyInfo().getX509DataArray() != null) {
              X509DataType[] x509s = key.getKeyInfo().getX509DataArray();

              // SSODescriptor/KeyDescriptor/KeyInfo/X509Data
              for (X509DataType x509 : x509s) {
                if (x509.getX509CertificateArray() != null) {
                  byte[][] x509bytesArray = x509.getX509CertificateArray();

                  // SSODescriptor/KeyDescriptor/KeyInfo/X509Data/X509Certificate
                  try {
                    CertificateFactory certFactory = CertificateFactory.getInstance("x.509");

                    for (byte[] x509bytes : x509bytesArray) {
                      ByteArrayInputStream certByteStream = new ByteArrayInputStream(x509bytes);
                      X509Certificate x509CertFromMetadata = (X509Certificate)certFactory.generateCertificate(certByteStream);
                      certByteStream.close();

                      x509Certs.add(x509CertFromMetadata);
                    } // for (byte[] x509bytes : x509bytesArray)
                  }
                  catch(CertificateException ce) {
                    logger.error("Error obtaining certificate factory", ce);
                    throw new GuanxiException(ce);
                  }
                  catch(IOException ioe) {
                    logger.error("Error closing certificate byte stream", ioe);
                    throw new GuanxiException(ioe);
                  }
                } // if (x509.getX509CertificateArray() != null)
              }
            }
          }
        }
      } // for (SSODescriptorType idpInfo : idpInfos)

    X509Certificate[] x509sFromMetadata = new X509Certificate[x509Certs.size()];
    x509Certs.copyInto(x509sFromMetadata);
    return x509sFromMetadata;
  }

  /**
   * Extracts KeyNames from a SAML2 IdP EntityDescriptor
   *
   * @param saml2Metadata The SAML2 metadata which may contain the certificates
   * @return array of String objects created from the metadata
   * @throws GuanxiException if an error occurs
   */
  public static String[] getKeyNamesFromIdPMetadata(EntityDescriptorType saml2Metadata) throws GuanxiException {
    return getKeyNamesFromMetadata(saml2Metadata.getIDPSSODescriptorArray());
  }

  /**
   * Extracts KeyNames from a SAML2 SP EntityDescriptor
   *
   * @param saml2Metadata The SAML2 metadata which may contain the certificates
   * @return array of String objects created from the metadata
   * @throws GuanxiException if an error occurs
   */
  public static String[] getKeyNamesFromSPMetadata(EntityDescriptorType saml2Metadata) throws GuanxiException {
    return getKeyNamesFromMetadata(saml2Metadata.getSPSSODescriptorArray());
  }

  /**
   * Extracts KeyNames from a SAML2 EntityDescriptor
   *
   * @param entityDescriptors The SAML2 metadata which may contain the key names. This can either be
   * an IDPSSODescriptor or an SPSSODescriptor
   * @return array of String objects created from the metadata
   * @throws GuanxiException if an error occurs
   */
  public static String[] getKeyNamesFromMetadata(SSODescriptorType[] entityDescriptors) throws GuanxiException {
    if (entityDescriptors == null) {
      return null;
    }

    Vector<String> keyNames = new Vector<String>();

      for (SSODescriptorType entityDescriptor : entityDescriptors) {
        KeyDescriptorType[] keys = entityDescriptor.getKeyDescriptorArray();

        // SSODescriptor/KeyDescriptor
        for (KeyDescriptorType key : keys) {
          if (key.getKeyInfo() != null) {
            // SSODescriptor/KeyDescriptor/KeyInfo
            if (key.getKeyInfo().getKeyNameArray() != null) {
              keyNames.addAll(Arrays.asList(key.getKeyInfo().getKeyNameArray()));
            }
          }
        }
      } // for (SSODescriptorType idpInfo : idpInfos)

    String[] keyNamesFromMetadata = new String[keyNames.size()];
    keyNames.copyInto(keyNamesFromMetadata);
    return keyNamesFromMetadata;
  }
}
openssl pkcs12 -export \
    -inkey fedora-key.pem -in fedora-cert.pem \
    -out client.pfx -passout pass:PASSWORD \
    -name qlikClient

keytool -importkeystore \
    -destkeystore truststore.pfx -deststoretype PKCS12 -deststorepass PASSWORD \
    -srckeystore client.pfx -srcstorepass PASSWORD -srcstoretype PKCS12 \
    -alias qlikClient

keytool -importcert \
    -keystore truststore.pfx -storepass PASSWORD \
    -file cacert.pem -noprompt \
    -alias qlikServerCACert

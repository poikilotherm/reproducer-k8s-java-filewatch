FROM payara/server-full:5.2020.7

USER root
RUN mkdir /test && chown payara: /test

USER payara
COPY maven/k8s-watch.war ${DEPLOY_DIR}

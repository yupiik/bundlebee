FROM ossyupiik/java:17.0.9.1
RUN mkdir -p /opt/yupiik/bundlebee/lib /opt/yupiik/bundlebee/bin && \
    wget https://repo.maven.apache.org/maven2/io/yupiik/bundlebee-core/1.0.24/bundlebee-core-1.0.24-fat.jar -O /opt/yupiik/bundlebee/lib/bundlebee.jar <1>
COPY ./bundlebee.argocd-plugin.yaml /home/argocd/cmp-server/config/plugin.yaml <2>
COPY ./bundlebee.plugin.sh /opt/yupiik/bundlebee/bin/bundlebee.sh
RUN chmod +x /opt/yupiik/bundlebee/bin/*.sh && chown 999:0 -R /opt/yupiik/
USER 999
ENTRYPOINT ["/opt/yupiik/bundlebee/bin/bundlebee.sh"]

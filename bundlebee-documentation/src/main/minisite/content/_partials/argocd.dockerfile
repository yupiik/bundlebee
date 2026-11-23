FROM eclipse-temurin:21-jre
RUN mkdir -p /opt/yupiik/bundlebee/lib /opt/yupiik/bundlebee/bin
COPY ./bundlebee.jar /opt/yupiik/bundlebee/lib/bundlebee.jar <1>
COPY ./bundlebee.argocd-plugin.yaml /home/argocd/cmp-server/config/plugin.yaml <2>
COPY ./bundlebee.plugin.sh /opt/yupiik/bundlebee/bin/bundlebee.sh
COPY ./bundlebee.placeholders.sh /opt/yupiik/bundlebee/bin/bundlebee.placeholders.sh
RUN chmod +x /opt/yupiik/bundlebee/bin/*.sh && chown 999:0 -R /opt/yupiik/
USER 999
ENTRYPOINT ["/opt/yupiik/bundlebee/bin/bundlebee.sh"]

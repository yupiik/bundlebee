#! /bin/sh

java -Dio.yupiik.bundlebee.level=WARNING -jar \
    /opt/yupiik/bundlebee/lib/bundlebee.jar process \
    --injectTimestamp false --injectBundleBeeMetadata false \
    --output stdout \
    --bundlebee-kube-namespace "${ARGOCD_APP_NAMESPACE}" \
    --from "$ARGOCD_APP_SOURCE_PATH" --manifest "${ARGOCD_APP_SOURCE_PATH}/bundlebee/manifest.json" --alveolus "$ARGOCD_APP_NAME"
#!/bin/bash
set -e

VAULT_TOKEN=$(cat /etc/vault-token-dsde)

docker run --rm -e VAULT_TOKEN=$VAULT_TOKEN \
	broadinstitute/dsde-toolbox vault read -format=json secret/dsp/service-accts/firecloud/terraform-dev \
    | jq '.data' > service-account.json

#get startup script to run on new instance
curl https://raw.githubusercontent.com/broadinstitute/cromwell/db_perf_scripts/scripts/perf/startup_script.sh > startup_script.sh

DB_PASS=`docker run --rm -e VAULT_TOKEN=$VAULT_TOKEN \
	broadinstitute/dsde-toolbox vault read -format=json secret/dsp/cromwell/perf | jq '.data.db_pass'`

docker run --name perf_gcloud_$BUILD_NUMBER --rm -i -t google/cloud-sdk:slim /bin/bash -c "\
    gcloud auth activate-service-account --key-file sa.json;\
gcloud \
    --verbosity info \
    --project broad-dsde-cromwell-perf \
    compute \
    instances \
    create perf-test-$BUILD_NUMBER \
    --zone us-central1-c \
    --source-instance-template cromwell-perf-template-update \
    --metadata-from-file startup-script=startup_script.sh \
    --metadata \
        CROMWELL_DB_USER=cromwell,CROMWELL_DB_PASS=$DB_PASS,CLOUD_SQL_INSTANCE=cromwell-db-$BUILD_NUMBER,CROMWELL_VERSION=34,CROMWELL_PROJECT=broad-dsde-cromwell-perf,CROMWELL_BUCKET=gs://debtest3/,CROMWELL_STATSD_HOST=broad.io/batch-grafana,CROMWELL_STATSD_PORT=8125"

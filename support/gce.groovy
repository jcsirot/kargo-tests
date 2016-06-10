def run_coreos(username, credentials_id, project_id, service_account_email, gce_pem_id, image, network_plugin) {
    run(username, credentials_id, project_id, service_account_email, gce_pem_id, image, network_plugin, true)
}

def run(username, credentials_id, project_id, service_account_email, gce_pem_id, image, network_plugin, coreos=false) {
    def run_id = "${env.JOB_NAME}-${env.BUILD_NUMBER}"
    wrap([$class: 'AnsiColorBuildWrapper', colorMapName: "xterm"]) {
        withEnv(['PYTHONUNBUFFERED=1']) {
            try {
                create_vm(run_id, project_id, service_account_email, gce_pem_id, image)
                install_cluster(username, credentials_id, network_plugin, coreos)
                run_tests(credentials_id)
            } finally {
                delete_vm(run_id, project_id, service_account_email, gce_pem_id)
            }
        }
    }
}

def create_vm(run_id, project_id, service_account_email, gce_pem_id, image) {
    stage 'Provision'
    withCredentials([[$class: 'FileBinding', credentialsId: gce_pem_id, variable: 'GCE_PEM']]) {
        sh "kargo gce -y --path kargo --pem_file ${env.GCE_PEM} --email \"${service_account_email}\" --zone us-central1-a --type \"n1-standard-1\" --image \"${image}\" --project ${project_id} --tags ci --instances 3"
    }
}

def delete_vm(run_id, project_id, service_account_email, gce_pem_id) {
    stage 'Delete'
    withCredentials([[$class: 'FileBinding', credentialsId: gce_pem_id, variable: 'GCE_PEM']]) {
        ansiblePlaybook(
            inventory: 'kargo/inventory/inventory.cfg',
            playbook: 'playbooks/delete-gce.yml',
            extraVars: [
                gce_project_id: [value: project_id, hidden: true],
                gce_service_account_email: [value: service_account_email, hidden: true],
                gce_pem_file: "${env.GCE_PEM}",
            ],
            colorized: true
        )
    }
}

def run_tests(credentials_id) {
  stage 'Test'
  test_apiserver(credentials_id)
  test_create_pod(credentials_id)
  test_network(credentials_id)
}

def install_cluster(username, credentials_id, network_plugin, coreos=false) {
  stage 'Deploy'
  withCredentials([[$class: 'FileBinding', credentialsId: credentials_id, variable: 'SSH_KEY']]) {
    if (coreos) {
      sh "kargo deploy -y --path kargo --gce --coreos -u ${username} -k ${env.SSH_KEY}"
    }
    sh "kargo deploy -y --path kargo --gce -n ${network_plugin} -u ${username} -k ${env.SSH_KEY}"
  }
}

def test_apiserver(credentials_id) {
    ansiblePlaybook(
        inventory: 'kargo/inventory/inventory.cfg',
        playbook: 'testcases/010_check-apiserver.yml',
        credentialsId: credentials_id,
        colorized: true
    )
}

def test_create_pod(credentials_id) {
    ansiblePlaybook(
        inventory: 'kargo/inventory/inventory.cfg',
        playbook: 'testcases/020_check-create-pod.yml',
        sudo: true,
        credentialsId: credentials_id,
        colorized: true
    )
}

def test_network(credentials_id) {
    ansiblePlaybook(
        inventory: 'kargo/inventory/inventory.cfg',
        playbook: 'testcases/030_check-network.yml',
        sudo: true,
        credentialsId: credentials_id,
        colorized: true
    )
}

return this;

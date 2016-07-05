import com.cloudbees.groovy.cps.NonCPS

def run_coreos(username, credentials_id, project_id, service_account_email, gce_pem_id, image, network_plugin, deploy_options=[:]) {
    run(username, credentials_id, project_id, service_account_email, gce_pem_id, image, network_plugin, deploy_options, true)
}

def run(username, credentials_id, project_id, service_account_email, gce_pem_id, image, network_plugin, deploy_options=[:], coreos=false) {
    def run_id = "${env.JOB_NAME}-${env.BUILD_NUMBER}"
    wrap([$class: 'AnsiColorBuildWrapper', colorMapName: "xterm"]) {
        withEnv(['PYTHONUNBUFFERED=1']) {
            try {
                create_vm(run_id, project_id, service_account_email, gce_pem_id, image)
                install_cluster(username, credentials_id, network_plugin, deploy_options, coreos)
                run_tests(credentials_id, coreos)
            } finally {
                delete_vm(run_id, project_id, service_account_email, gce_pem_id)
            }
        }
    }
}

def create_vm(run_id, project_id, service_account_email, gce_pem_id, image) {
    stage 'Provision'
    withCredentials([[$class: 'FileBinding', credentialsId: gce_pem_id, variable: 'GCE_PEM']]) {
        sh "kargo gce -y --noclone --path kargo --pem_file ${env.GCE_PEM} --email \"${service_account_email}\" --zone us-central1-a --type \"n1-standard-1\" --image \"${image}\" --project ${project_id} --tags ci --instances 3"
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

def install_cluster(username, credentials_id, network_plugin, deploy_options=[:], coreos=false) {
  stage 'Deploy'
  coreosArg = coreos ? "--coreos" : ""
  extraArgs = generate_extra_args(deploy_options);
  echo extraArgs
  withCredentials([[$class: 'FileBinding', credentialsId: credentials_id, variable: 'SSH_KEY']]) {
    if (extraArgs.isEmpty()) {
      sh "kargo deploy -y --path kargo ${coreosArg} --gce -n ${network_plugin} -u ${username} -k ${env.SSH_KEY}"
    } else {
      sh "kargo deploy -y --path kargo ${coreosArg} --gce -n ${network_plugin} -u ${username} -k ${env.SSH_KEY} --ansible-opts \"${extraArgs}\""
    }
  }
}

@NonCPS
def generate_extra_args(deploy_options) {
  extraArgs = ""
  if (! deploy_options.isEmpty()) {
    extraArgs = deploy_options.collect { k,v -> "-e $k=$v" }.join(' ')
  }
  return extraArgs
}

def run_tests(credentials_id, coreos=false) {
  stage 'Test'
  vars = [:]
  if (coreos) {
    vars = [
      ansible_python_interpreter: "/opt/bin/python",
      kubectl_path: "/opt/bin/kubectl"
    ]
  }
  if (! coreos) {
    test_apiserver(credentials_id, vars)
  } else {
    echo "Skipping test_apiserver for CoreOS... (waiting for https://github.com/ansible/ansible/pull/11810)"
  }
  test_create_pod(credentials_id, vars)
  test_network(credentials_id, vars)
}

def test_apiserver(credentials_id, vars) {
    ansiblePlaybook(
        inventory: 'kargo/inventory/inventory.cfg',
        playbook: 'testcases/010_check-apiserver.yml',
        credentialsId: credentials_id,
        colorized: true,
        extraVars: vars
    )
}

def test_create_pod(credentials_id, vars) {
    ansiblePlaybook(
        inventory: 'kargo/inventory/inventory.cfg',
        playbook: 'testcases/020_check-create-pod.yml',
        sudo: true,
        credentialsId: credentials_id,
        colorized: true,
        extraVars: vars
    )
}

def test_network(credentials_id, vars) {
    ansiblePlaybook(
        inventory: 'kargo/inventory/inventory.cfg',
        playbook: 'testcases/030_check-network.yml',
        sudo: true,
        credentialsId: credentials_id,
        colorized: true,
        extraVars: vars
    )
}

return this;

import com.cloudbees.groovy.cps.NonCPS

def call(config) {
  // def config = [:]
  // closure.resolveStrategy = Closure.DELEGATE_FIRST
  // closure.delegate = config
  // closure()

  def run_id = "${env.JOB_NAME}-${env.BUILD_NUMBER}"
  node {
    git 'https://github.com/jcsirot/kargo-tests.git'
    dir('kargo') {
        git 'https://github.com/kubespray/kargo.git'
    }
    wrap([$class: 'AnsiColorBuildWrapper', colorMapName: "xterm"]) {
      withEnv(['PYTHONUNBUFFERED=1']) {
        try {
          echo "$config"
          create_vm(config)
          install_cluster(config)
          // run_tests(credentials_id, coreos)
        } finally {
          delete_vm(config)
        }
      }
    }
  }
}

def create_vm(config) {
  stage 'Provision'
  withCredentials([[$class: 'FileBinding', credentialsId: config.gce.pem_id, variable: 'GCE_PEM']]) {
    sh "kargo gce -y --noclone --path kargo --pem_file ${env.GCE_PEM} --email \"${config.gce.service_account_email}\" --zone us-central1-a --image \"${config.gce.image}\" --project ${config.gce.project_id} --tags ci --tags https-server --nodes 3 --nodes-machine-type n1-standard-1"
  }
}

def delete_vm(config /* run_id, project_id, service_account_email, gce_pem_id */) {
  stage 'Delete'
  try {
    withCredentials([[$class: 'FileBinding', credentialsId: config.gce.pem_id, variable: 'GCE_PEM']]) {
      ansiblePlaybook(
        inventory: 'kargo/inventory/inventory.cfg',
        playbook: 'playbooks/delete-gce.yml',
        extraVars: [
          gce_project_id: [value: config.gce.project_id, hidden: true],
          gce_service_account_email: [value: config.gce.service_account_email, hidden: true],
          gce_pem_file: "${env.GCE_PEM}",
        ],
        colorized: true
      )
    }
  } catch (ex) {
    echo 'An unexpected error occurred when deleting the test VMs. Please look at https://console.cloud.google.com/'
  }
}

def install_cluster(config /* username, credentials_id, network_plugin, deploy_options=[:], coreos=false */) {
  stage 'Deploy'
  coreosArg = (config.coreos != null && config.coreos) ? "--coreos" : ""
  withCredentials([[$class: 'FileBinding', credentialsId: config.credentials_id, variable: 'SSH_KEY']]) {
    if (config.options == null || config.options.isEmpty()) {
      sh "kargo deploy -y --path kargo ${coreosArg} --gce -n ${config.network_plugin} -u ${config.username} -k ${env.SSH_KEY}"
    } else {
      extraArgs = generate_extra_args(config.options);
      sh "kargo deploy -y --path kargo ${coreosArg} --gce -n ${config.network_plugin} -u ${config.username} -k ${env.SSH_KEY} --ansible-opts \"${extraArgs}\""
    }
  }
}

@NonCPS
def generate_extra_args(deploy_options) {
  return deploy_options.collect { k,v -> "-e $k=$v" }.join(' ')
}

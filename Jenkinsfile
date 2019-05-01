pipeline {
  agent any
  stages {
    stage('Test emulator') {
      parallel {
        stage('Test emulator') {
          steps {
            dir(path: 'emulator') {
              sh './gradlew build'
            }

          }
        }
        stage('Test explorer') {
          steps {
            dir(path: 'explorer') {
              sh './gradlew build'
            }

          }
        }
      }
    }
  }
  post {
    always {
      slackSend(
        channel: craig.pastro,
        color: 'good',
        message: "All done!")
    }
  }
}

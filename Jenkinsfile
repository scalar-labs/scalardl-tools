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
    stage('Notify') {
      steps {
        sh 'echo \'All done!\''
        slackSend(attachments: 'Blah')
      }
    }
  }
}
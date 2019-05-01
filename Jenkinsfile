pipeline {
  agent any
  stages {
    stage('Go to Emulator') {
      steps {
        dir(path: 'emulator') {
          pwd()
        }

        sh 'echo $(pwd)'
      }
    }
    stage('Assemble') {
      steps {
        sh './gradlew assemble'
      }
    }
    stage('Test') {
      steps {
        sh './gradlew test'
      }
    }
    stage('Notify') {
      steps {
        mail(to: 'siyopao@gmail.com', from: 'jenkins', subject: 'Build finished', body: 'Hi there!')
      }
    }
  }
}
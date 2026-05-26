import { CapacitorConfig } from '@capacitor/cli'
import fs from 'fs'

const packageJsonPath = fs.existsSync('static/package.json')
  ? 'static/package.json'
  : 'public/static/package.json'
const version = fs.readFileSync(packageJsonPath, 'utf8').match(/"version": "(.*?)"/)?.at(1) ?? '0.0.0'
const journalAndroid = process.env.LOGSEQ_JOURNAL_ANDROID === '1' || process.env.LOGSEQ_JOURNAL_ANDROID === 'true'

const config: CapacitorConfig = {
  appId: journalAndroid ? 'dev.patopo.journal' : 'com.logseq.app',
  appName: journalAndroid ? 'Journal' : 'Logseq',
  bundledWebRuntime: false,
  webDir: 'public',
  plugins: {
    SplashScreen: {
      launchShowDuration: 500,
      launchAutoHide: false,
      androidScaleType: 'CENTER_CROP',
      splashImmersive: false,
      backgroundColor: '#002b36'
    },

    Keyboard: {
      resize: 'none'
    }
  },
  android: {
    appendUserAgent: `Logseq/${version} (Android)`
  },
  ios: {
    scheme: 'Logseq',
    appendUserAgent: `Logseq/${version} (iOS)`
  },
  cordova: {
    staticPlugins: [
      '@logseq/capacitor-file-sync', // AgeEncryption requires static link
    ]
  }
}

if (!journalAndroid && process.env.LOGSEQ_APP_SERVER_URL) {
  Object.assign(config, {
    server: {
      url: process.env.LOGSEQ_APP_SERVER_URL,
      cleartext: true
    }
  })
}

export = config;

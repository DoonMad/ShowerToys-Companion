# Showertoys Companion

The official Android companion app for the Showertoys desktop application. 📱 <-> 💻

## Description

This app connects to the Showertoys desktop utility running on your PC over your local Wi-Fi network. It enables features like clipboard synchronization.

## Features (v1.0)

* **Connect via QR Code:** Easily pair with the Showertoys desktop app by scanning the QR code displayed in the 'Local File Share' module.
* **Receive Clipboard Sync:** Automatically receives text copied on the connected PC and updates the phone's clipboard (requires the app or its background service to be active).

## Download

* **[Download the latest APK (v1.0.0)](https://github.com/DoonMad/ShowerToys-Companion/releases/download/v1.0.0/app-debug.apk)**

## How to Use

1.  Ensure your phone and PC running Showertoys are connected to the **same Wi-Fi network**.
2.  Open the Showertoys desktop application on your PC.
3.  Navigate to the 'Local File Share' module and click **"Start Server"**.
4.  Open the Showertoys Companion app on your phone.
5.  Tap the **"Scan PC QR Code"** button and scan the QR code displayed on your PC screen.
6.  The app will connect, and the status should change to "Connected". Clipboard text copied on the PC will now be sent to your phone.

## Desktop Application

This is the companion app for the main Showertoys desktop utility (built with C++/Qt).

Find the main Showertoys desktop app repository here:
`https://github.com/DoonMad/ShowerToys-Companion`

## Building

This project is built using Android Studio with Kotlin. Open the project folder in Android Studio to build or modify the app.
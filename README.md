# capacitor-callkit-plugin

Capacitor plugin for native ui for answering and delicing calls by providing PushKit functionality to ionic capacitor

## Install
1. Install plugin

```bash
npm install capacitor-callkit-plugin
npx cap sync
```

2. Xcode Project > Capabilities pane. Select the checkbox for Voice over IP, as shown in Image

![](https://miro.medium.com/max/700/1*zVc9U601x_qUqweRKfsfow.png)

3. Register certificate on  [developer.apple.com/certificates](https://developer.apple.com/certificates) 

![](https://miro.medium.com/max/700/1*Z2q66Vo2Emho4_IVXRN8GQ.png)

4. Download the certificate and open it to import it into the Keychain Access app.

5. Export certificates as shown bellow 

![](https://miro.medium.com/max/700/1*7N7d7-dEa6WAMzWbFXO66A.png)

6. Now, navigate to the folder where you exported this file and execute following command:
```bash
openssl pkcs12 -in YOUR_CERTIFICATES.p12 -out app.pem -nodes -clcerts
```

7. You will receive `app.pem` certificate file that can be used to send VOIP notification (you can use my script bellow)

## Usage

To make this plugin work, you need to call `.register()` method and then you can use API bellow.

```typescript
import { CallKit } from 'capacitor-callkit-plugin';

async function plugin_events(status, data){
    try {
        switch (status) {
            case "on_call_accepted":
                console.log(`Call has been received from ${data.username} (connectionId: ${data.connectionId})`)
                break;
            case "on_call_rejected":
                console.log(`Call has been rejected from ${data.username} (connectionId: ${data.connectionId})`)
                break;
            case "on_error":
                console.error("Call Plugin Error ", data.error)
                break;
            case "on_token":
                console.log(`VOIP token has been received ${data.token}`)
                break;
            case "on_registration":
                console.log(`Push notification has been registered (uuid: ${data.uuid})`)
                break;
        }
    } catch (error) {
        console.log(error)
    }
}

try {
    await CallKit.addListener("plugin_events", (response) => {
        plugin_events(response.status, response)
    })
} catch (error) {
    console.log(error)
}
```

Once the plugin is installed, the only thing that you need to do is to push a VOIP notification with the following data payload structure:

```json
{
    "error"      : "Error message",
    "Username"      : "Display Name",
    "token"      : "Device Token",
    "ConnectionId"  : "Unique Call ID"
}
```

You can use my script (bellow) to test it out: 
`./voip.sh <connectionId> <deviceToken> <bundleId> <hasVideo> <username>`
eg: `./voip.sh 123456789 <deviceToken> com.example.app false Anonymous`


### Pay attention:

- replace  <YOUR_BUNDLE_ID> with your app bundle 
- ensure that you are using correct voip certificate (specified in `--cert app.pem`)
- if you'll go to production version, you will need to do request to `api.push.apple.com/3/device/${token}` instead of
  `api.development.push.apple.com/3/device/${token}`, otherwise you will receive `BadDeviceToken` issue
  

## API

* [`register()`](#register)
* [`addListener("plugin_events", handler)`](#addlistener)
* [Interfaces](#interfaces)

<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->


### addListener("plugin_events", handler)

Adds listener. When device will be registered to receiving VOIP push notifications, `plugin_events` will be called.

As usually, it's called after the app has been loaded on the device

```typescript
import { CallKit } from 'capacitor-callkit-plugin';
//...
await CallKit.addListener("plugin_events", (response) => {
    console.log(response)
})
```

**Returns:** <code>any</code>

--------------------

### Interfaces

#### CallData

| Prop               | Type                |
| ------------------ | ------------------- |
| **`connectionId`** | <code>string</code> |
| **`username`**     | <code>string</code> |
| **`error`** | <code>string</code> |
| **`uid`** | <code>string</code> |
| **`token`** | <code>string</code> |

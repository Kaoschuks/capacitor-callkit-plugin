import { CallKit } from 'capacitor-callkit-plugin';

const plugin_events = (status, data) => {
    try {
        switch (status) {
            case "on_call_accepted":
                console.log("Call Accepted for " + data)
                alert(`Call accepted from ${data.username}`)
                break;
            case "on_call_rejected":
                console.log("Call Rejected for " + data)
                alert(`Call rejected from ${data.username}`)
                break;
            case "on_error":
                console.error("Call Plugin Error ", data.error)
                break;
            case "on_token":
                var token = document.getElementById('token')
                token.innerHTML = `Token Value: ${resp.token || response.token}`
                console.info("Call Plugin Token generated ", data.token)
                break;
            case "on_registration":
                console.info("Call Plugin Registration on Device", data)
                break;
            default:
                console.log(data)
                break;
        }
    } catch (error) {
        console.log(error)
    }
}

try {
    CallKit.addListener("plugin_events", (response) => {
        plugin_events(response.status, response)
    })
} catch (error) {
    console.log(error)
}

import { CallKit } from 'capacitor-callkit-plugin';

CallKit.addListener("plugin_events", (response) => {
    plugin_events(response.status, response)
})
window.registerCallKit = async () => {
    try {
        const resp = await CallKit.register()
        console.log(resp.status)
        console.log(resp.token)
    } catch (error) {
        console.log(error)
    }
}

const plugin_events = (status, data) => {
    try {
        switch (status) {
            case "on_call_accepted":
                console.log("Call Accepted for " + data)
                break;
            case "on_call_rejected":
                console.log("Call Rejected for " + data)
                break;
            case "on_error":
                console.error("Call Plugin Error ", data.error)
                break;
            case "on_token":
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

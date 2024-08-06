import { CallKit } from 'capacitor-callkit-plugin';

window.testEcho = () => {
    const inputValue = document.getElementById("echoInput").value;
    CallKit.echo({ value: inputValue })
}

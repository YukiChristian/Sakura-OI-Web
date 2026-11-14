"""Runs a JavaScript expression in the app's WebView (debug builds) through its DevTools socket."""
import json
import sys
import urllib.request

import websocket

pages = json.load(urllib.request.urlopen('http://127.0.0.1:9333/json'))
ws = websocket.create_connection(pages[0]['webSocketDebuggerUrl'], suppress_origin=True)
ws.send(json.dumps({'id': 1, 'method': 'Runtime.evaluate',
                    'params': {'expression': sys.argv[1], 'returnByValue': True, 'awaitPromise': True}}))
r = json.loads(ws.recv())['result']
print(r.get('result', {}).get('value', r))

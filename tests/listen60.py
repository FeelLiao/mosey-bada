import socket, struct, time
s = socket.socket()
s.settimeout(60)
s.connect(('127.0.0.1', 19539))
def S(c,p=b''):
    pl=bytes([c])+p
    s.sendall(struct.pack('<BI',1,len(pl))+pl)
    h=s.recv(5); pl=struct.unpack('<I',h[1:5])[0]; d=s.recv(pl)
    return struct.unpack('<i',d[:4])[0]
S(0); S(3,b'US')
print('start:', S(1, bytes([3])+struct.pack('<i',149)+struct.pack('<i',44)+struct.pack('<i',6)))
print('=== Listen 60s ===')
print('Mac AirDrop ON!')
n=0; t0=time.time()
while time.time()-t0<60:
    try:
        h=s.recv(5)
        t=h[0]; pl2=struct.unpack('<I',h[1:5])[0]; d=s.recv(pl2) if pl2>0 else b''
        if t==3:
            n+=1; print(f'EVENT #{n}: {len(d)}b hex={d.hex()}')
    except: continue
print(f'Total: {n}')
print('stop:', S(2))

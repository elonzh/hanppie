
__import__ = rm_log.__dict__["__builtins__"]["__import__"]


def import_string(import_name):
    """
    import function for unsafe modules
    """
    import_name = str(import_name).replace(":", ".")
    try:
        __import__(import_name)
    except ImportError:
        if "." not in import_name:
            raise
    else:
        return sys.modules[import_name]

    module_name, obj_name = import_name.rsplit(".", 1)
    module = __import__(module_name, globals(), locals(), [obj_name])
    try:
        return getattr(module, obj_name)
    except AttributeError as e:
        raise ImportError(e)

def serve_files(port=8088):
    logger.debug("starting serve_files")
    server = import_string("http.server")

    def do_GET(self):
        print("Get:" + self.path)
        if self.path == "/cmd?exit":
            exit()
        f = self.send_head()
        if f:
            try:
                self.copyfile(f, self.wfile)
            finally:
                f.close()

    handle = server.SimpleHTTPRequestHandler
    handle.do_GET = do_GET
    protocol = "HTTP/1.0"
    server_address = ("", port)
    handle.protocol_version = protocol
    with server.HTTPServer(server_address, handle) as httpd:
        try:
            httpd.serve_forever()
        except:
            print("httpd error")


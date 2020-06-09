from rm_log import dji_scratch_logger_get
from tools import byte_to_float
from tools import byte_to_int32
from tools import byte_to_string
from widget_define import *
from widget_module import Widget

logger = dji_scratch_logger_get()


def byte_to_value(type, byte):
    if type == 'bool':
        return byte[0]
    elif type == 'int32':
        return byte_to_int32(byte)
    elif type == 'float':
        return byte_to_float(byte)
    elif type == 'string':
        return byte_to_string(byte)


class Position(object):
    def __init__(self):
        self.x = 0
        self.y = 0

    def update(self, x, y):
        self.x = x
        self.y = y

    def get(self):
        return self.x, self.y


class Size(object):
    def __init__(self):
        self.w = 0
        self.h = 0

    def update(self, w, h):
        self.w = w
        self.h = h

    def get(self):
        return self.w, self.h


class Color(object):
    def __init__(self):
        self.r = 0
        self.g = 0
        self.b = 0
        self.a = 0

    def update(self, r, g, b, a):
        self.r = r
        self.g = g
        self.b = b
        self.a = a

    def get(self):
        return self.r, self.g, self.b, self.a


class Attribute(object):
    def __init__(self):
        self.name = ''
        self.active = None
        self.on_stage = None
        self.position = Position()
        self.pivot = Position()
        self.size = Size()
        self.color = Color()
        self.order = 0
        self.rotation = 0

    def update_name(self, name):
        self.name = name

    def get_name(self):
        return self.name

    def update_active(self, flag):
        self.active = flag

    def get_active(self, flag):
        return self.active

    def update_position(self, x, y):
        self.position.update(x, y)

    def get_position(self):
        return self.position.get()

    def update_size(self, w, h):
        self.size.update(w, h)

    def get_size(self):
        return self.size.get()

    def update_color(self, r, g, b, a):
        self.color.update(r, g, b, a)

    def get_color(self):
        return self.color.get()

    def update_rotation(self, d):
        self.rotation = d

    def get_rotation(self):
        return self.rotation

    def update_pivot(self, x, y):
        self.pivot.update(x, y)

    def get_pivot(self):
        return self.pivot.get()

    def update_order(self, order):
        self.order = order

    def get_order(self):
        return self.order


class WidgetBase(Attribute):
    def __init__(self, event_client, type, index):
        super(WidgetBase, self).__init__()
        self.type = type
        self.index = index
        self.widget = Widget(event_client)

        self.action_trigger_dict = {}
        self.action_enum_dict = {}
        self.action_value_type_list_dict = {}

        self.action_trigger_process_callback_register(self.__action_trigger_process)

    def create(self):
        return self.set_custom_attribute(widget_public_function.create)

    def destory(self):
        return self.set_custom_attribute(widget_public_function.destory)

    def get_type(self):
        return self.type

    def get_index(self):
        return self.index

    def set_active(self, flag):
        self.update_active(flag)
        params = (
            ('bool', flag),
        )
        return self.set_custom_attribute(widget_public_function.active)

    def set_name(self, name):
        name = name[0:64]
        self.update_name(name)
        params = (
            ('string', name),
        )
        return self.set_custom_attribute(widget_public_function.name, params)

    def set_position(self, x, y):
        self.update_position(x, y)
        params = (
            ('float', x),
            ('float', y),
        )
        return self.set_custom_attribute(widget_public_function.position, params)

    def set_size(self, w, h):
        self.update_size(w, h)
        params = (
            ('int32', w),
            ('int32', h),
        )
        return self.set_custom_attribute(widget_public_function.size, params)

    def set_rotation(self, d):
        self.update_rotation(d)
        params = (
            ('float', d),
        )
        return self.set_custom_attribute(widget_public_function.rotation, params)

    def set_pivot(self, x, y):
        self.update_pivot(d)
        params = (
            ('int32', x),
            ('int32', y),
        )
        return self.set_custom_attribute(widget_public_function.pivot, params)

    def set_order(self, order):
        self.update_order(d)
        params = (
            ('int32', order),
        )
        return self.set_custom_attribute(widget_public_function.order, params)

    def test(self, v1, v2, v3, v4):
        params = (
            ('bool', v1),
            ('int32', v2),
            ('float', v3),
            ('string', v4),
        )
        return self.set_custom_attribute(widget_public_function.test, params)

    def set_custom_attribute(self, function_enum, params=()):
        return self.widget.attribute_set(self.type, self.index, function_enum, params)

    def action_trigger_process_callback_register(self, cb):
        self.widget.action_trigger_callback_register(cb)

    def action_trigger_process_callback_unregister(self, cb):
        self.widget.action_trigger_callback_unregister(cb)

    def callback_register(self, action, cb):
        if action in self.action_enum_dict.keys():
            if callable(cb):
                self.action_trigger_dict[self.action_enum_dict[action]] = cb

    def update_action_enum_dict(self, action_enum_dict):
        self.action_enum_dict = action_enum_dict

    def update_action_value_type_list_dict(self, action_value_type_list_dict):
        self.action_value_type_list_dict = action_value_type_list_dict

    def __action_trigger_process(self, event_client, msg):
        try:
            data = msg['data']
            type = data[0]
            index = data[1]
            action = data[2]

        except Exception as e:
            logger.fatal('action_trigger_preocess unpack error, error msg: %s' % e)

        try:
            if type == self.type and index == self.index:
                if action in self.action_trigger_dict.keys():
                    cb = self.action_trigger_dict[action]
                    if cb:
                        params_num = data[3]
                        data = data[4:]

                        params_type_list = self.action_value_type_list_dict[action]
                        user_data = []

                        if params_num != len(params_type_list):
                            logger.fatal(params_type_list)
                            logger.fatal(
                                'action_trigger_preocess error , params number parse error. cur num: %d tar num: %d' % (
                                params_num, len(params_type_list)))
                            return

                        for t in params_type_list:
                            param_length = data[1]
                            param_value = data[2:2 + param_length]
                            data = data[2 + param_length:]
                            param_value = byte_to_value(t, param_value)

                            user_data.append(param_value)

                        cb(self, *user_data)
                    event_client.resp_ok(msg)

        except Exception as e:
            logger.fatal('action_trigger_preocess error , error msg: %s' % str(e))

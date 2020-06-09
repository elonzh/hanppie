from widget_base import *
from widget_define import *

logger = dji_scratch_logger_get()

global_event_client = None
global_index = {}


def get_index(obj):
    global global_index
    if obj in global_index.keys():
        global_index[obj] += 1
    else:
        global_index[obj] = 0
    return global_index[obj]


def update_widget_global_event_client(event_client):
    global global_event_client
    global_event_client = event_client


def update_widget_global_index(index):
    global global_index
    global_index = {}


class Stage(WidgetBase):
    def __init__(self):
        global global_event_client
        super(Stage, self).__init__(global_event_client, widget_type.stage, get_index(Stage))

        self.create()

        self.stage_widget_list = []

    def add_widget(self, widget_obj):
        logger.info('add new widget: %s' % widget_obj)
        type = widget_obj.get_type()
        index = widget_obj.get_index()
        params = (
            ('int32', type),
            ('int32', index),
        )
        self.set_custom_attribute(widget_priviate_function.stage.add_widget, params)

        self.stage_widget_list.append(widget_obj)

    def remove_widget(self, widget_obj):
        logger.info('remove new widget: %s' % widget_obj)
        type = widget_obj.get_type()
        index = widget_obj.get_index()
        params = (
            ('int32', type),
            ('int32', index),
        )
        self.set_custom_attribute(widget_priviate_function.stage.add_widget, params)

        if widget_obj in self.stage_widget_list:
            self.stage_widget_list.remove(widget_obj)


class Button(WidgetBase):
    def __init__(self):
        global global_event_client
        super(Button, self).__init__(global_event_client, widget_type.button, get_index(Button))

        action_enum_dict = {
            'on_click': widget_action.button.on_click,
            'on_press_down': widget_action.button.on_press_down,
            'on_press_up': widget_action.button.on_press_up,
        }

        action_value_type_list_dict = {
            widget_action.button.on_click: (),
            widget_action.button.on_press_down: (),
            widget_action.button.on_press_up: (),
        }

        self.update_action_enum_dict(action_enum_dict)
        self.update_action_value_type_list_dict(action_value_type_list_dict)
        self.create()

    def set_text(self, text, text_color=None, text_align=None, text_size=None):
        params = (
            ('string', text),
        )
        result = self.set_custom_attribute(widget_priviate_function.button.set_text, params)

        result = 0

        if text_color:
            result = self.set_text_color(*text_color)
        if text_align:
            result = self.set_text_align(text_align)
        if text_size:
            result = self.set_text_size(text_size)

        return result

    def set_text_color(self, *text_color):
        if len(text_color) != 4:
            return False
        params = (
            ('int32', text_color[0]),
            ('int32', text_color[1]),
            ('int32', text_color[2]),
            ('int32', text_color[3]),
        )
        result = self.set_custom_attribute(widget_priviate_function.button.set_text_color, params)

    def set_text_align(self, text_align):
        params = (
            ('int32', text_align),
        )
        return self.set_custom_attribute(widget_priviate_function.button.set_text_align, params)

    def set_text_size(self, text_size):
        params = (
            ('int32', text_size),
        )
        return self.set_custom_attribute(widget_priviate_function.button.set_text_size, params)

    def set_background_color(self, *background_color):
        if len(background_color) != 4:
            return False
        params = (
            ('int32', background_color[0]),
            ('int32', background_color[1]),
            ('int32', background_color[2]),
            ('int32', background_color[3]),
        )
        return self.set_custom_attribute(widget_priviate_function.button.set_background_color, params)


class Toggle(WidgetBase):
    def __init__(self):
        global global_event_client
        super(Toggle, self).__init__(global_event_client, widget_type.toggle, get_index(Toggle))

        action_enum_dict = {
            'on_value_changed': widget_action.toggle.on_value_change,
        }
        action_value_type_list_dict = {
            widget_action.toggle.on_value_change: ('bool',),  # cur_value
        }

        self.update_action_enum_dict(action_enum_dict)
        self.update_action_value_type_list_dict(action_value_type_list_dict)
        self.create()

    def set_text(self, text, text_color=None, text_align=None, text_size=None):
        params = (
            ('string', text),
        )
        result = self.set_custom_attribute(widget_priviate_function.toggle.set_text, params)

        result = 0

        if text_color:
            result = self.set_text_color(*text_color)
        if text_align:
            result = self.set_text_align(text_align)
        if text_size:
            result = self.set_text_size(text_size)

        return result

    def set_text_color(self, *text_color):
        if len(text_color) != 4:
            return False
        params = (
            ('int32', text_color[0]),
            ('int32', text_color[1]),
            ('int32', text_color[2]),
            ('int32', text_color[3]),
        )
        result = self.set_custom_attribute(widget_priviate_function.toggle.set_text_color, params)

    def set_text_align(self, text_align):
        params = (
            ('int32', text_align),
        )
        return self.set_custom_attribute(widget_priviate_function.toggle.set_text_align, params)

    def set_text_size(self, text_size):
        params = (
            ('int32', text_size),
        )
        return self.set_custom_attribute(widget_priviate_function.toggle.set_text_size, params)

    def set_background_color(self, *background_color):
        if len(background_color) != 4:
            return False
        params = (
            ('int32', background_color[0]),
            ('int32', background_color[1]),
            ('int32', background_color[2]),
            ('int32', background_color[3]),
        )
        return self.set_custom_attribute(widget_priviate_function.toggle.set_background_color, params)

    def set_checkmark_color(self, *checkmark_color):
        if len(checkmark_color) != 4:
            return False
        params = (
            ('int32', checkmark_color[0]),
            ('int32', checkmark_color[1]),
            ('int32', checkmark_color[2]),
            ('int32', checkmark_color[3]),
        )
        return self.set_custom_attribute(widget_priviate_function.toggle.set_checkmark_color, params)

    def set_is_on(self, on):
        params = (
            ('bool', on),
        )
        return self.set_custom_attribute(widget_priviate_function.toggle.set_is_on, params)


class Slider(WidgetBase):
    def __init__(self):
        global global_event_client
        super(Slider, self).__init__(global_event_client, widget_type.slider, get_index(Slider))

        action_enum_dict = {
            'on_value_changed': widget_action.slider.on_value_change,
        }
        action_value_type_list_dict = {
            widget_action.slider.on_value_change: ('int32',),  # cur_value
        }

        self.update_action_enum_dict(action_enum_dict)
        self.update_action_value_type_list_dict(action_value_type_list_dict)
        self.create()

    def set_range(self, min_value, max_value):
        params = (
            ('int32', min_value),
            ('int32', max_value),
        )
        return self.set_custom_attribute(widget_priviate_function.slider.set_range, params)

    def set_background_color(self, *background_color):
        if len(background_color) != 4:
            return False
        params = (
            ('int32', background_color[0]),
            ('int32', background_color[1]),
            ('int32', background_color[2]),
            ('int32', background_color[3]),
        )

        return self.set_custom_attribute(widget_priviate_function.slider.set_background_color, params)

    def set_fill_color(self, *fill_color):
        if len(fill_color) != 4:
            return False
        params = (
            ('int32', fill_color[0]),
            ('int32', fill_color[1]),
            ('int32', fill_color[2]),
            ('int32', fill_color[3]),
        )

        return self.set_custom_attribute(widget_priviate_function.slider.set_fill_color, params)

    def set_handle_color(self, *handle_color):
        if len(handle_color) != 4:
            return False
        params = (
            ('int32', handle_color[0]),
            ('int32', handle_color[1]),
            ('int32', handle_color[2]),
            ('int32', handle_color[3]),
        )

        return self.set_custom_attribute(widget_priviate_function.slider.set_handle_color, params)


class Dropdown(WidgetBase):
    def __init__(self):
        global global_event_client
        super(Dropdown, self).__init__(global_event_client, widget_type.dropdown, get_index(Dropdown))

        action_enum_dict = {
            'on_value_changed': widget_action.dropdown.on_value_change,
        }
        action_value_type_list_dict = {
            widget_action.dropdown.on_value_change: ('int32',),  # cur_value
        }

        self.update_action_enum_dict(action_enum_dict)
        self.update_action_value_type_list_dict(action_value_type_list_dict)
        self.create()

    def set_options(self, *options):
        params = []
        for s in options:
            params.append(('string', str(s))),

        return self.set_custom_attribute(widget_priviate_function.dropdown.set_options, params)

    def set_background_color(self, *background_color):
        if len(background_color) != 4:
            return False
        params = (
            ('int32', background_color[0]),
            ('int32', background_color[1]),
            ('int32', background_color[2]),
            ('int32', background_color[3]),
        )

        return self.set_custom_attribute(widget_priviate_function.dropdown.set_background_color, params)

    def set_arrow_color(self, *arrow_color):
        if len(arrow_color) != 4:
            return False
        params = (
            ('int32', arrow_color[0]),
            ('int32', arrow_color[1]),
            ('int32', arrow_color[2]),
            ('int32', arrow_color[3]),
        )

        return self.set_custom_attribute(widget_priviate_function.dropdown.set_arrow_color, params)

    def set_item_text_color(self, *item_text_color):
        if len(item_text_color) != 4:
            return False
        params = (
            ('int32', item_text_color[0]),
            ('int32', item_text_color[1]),
            ('int32', item_text_color[2]),
            ('int32', item_text_color[3]),
        )

        return self.set_custom_attribute(widget_priviate_function.dropdown.set_item_text_color, params)

    def set_item_background_color(self, *item_background_color):
        if len(item_background_color) != 4:
            return False
        params = (
            ('int32', item_background_color[0]),
            ('int32', item_background_color[1]),
            ('int32', item_background_color[2]),
            ('int32', item_background_color[3]),
        )

        return self.set_custom_attribute(widget_priviate_function.dropdown.set_item_background_color, params)

    def set_item_checkmark_color(self, *item_checkmark_color):
        if len(item_checkmark_color) != 4:
            return False
        params = (
            ('int32', item_checkmark_color[0]),
            ('int32', item_checkmark_color[1]),
            ('int32', item_checkmark_color[2]),
            ('int32', item_checkmark_color[3]),
        )

        return self.set_custom_attribute(widget_priviate_function.dropdown.set_item_checkmark_color, params)


class Text(WidgetBase):
    def __init__(self):
        global global_event_client
        super(Text, self).__init__(global_event_client, widget_type.text, get_index(Text))

        action_enum_dict = {
        }
        action_value_type_list_dict = {
        }

        self.update_action_enum_dict(action_enum_dict)
        self.update_action_value_type_list_dict(action_value_type_list_dict)
        self.create()

    def set_text(self, text, text_color=None, text_align=None, text_size=None):
        text = text.replace('@n', '\n')
        params = (
            ('string', text),
        )
        result = self.set_custom_attribute(widget_priviate_function.text.set_text, params)

        result = 0

        if text_color:
            result = self.set_text_color(*text_color)
        if text_align:
            result = self.set_text_align(text_align)
        if text_size:
            result = self.set_text_size(text_size)

        return result

    def append_text(self, text):
        text = text.replace('@n', '\n')
        params = (
            ('string', text),
        )
        return self.set_custom_attribute(widget_priviate_function.text.append_text, params)

    def set_text_color(self, *text_color):
        if len(text_color) != 4:
            return False
        params = (
            ('int32', text_color[0]),
            ('int32', text_color[1]),
            ('int32', text_color[2]),
            ('int32', text_color[3]),
        )
        result = self.set_custom_attribute(widget_priviate_function.text.set_text_color, params)

    def set_text_align(self, text_align):
        params = (
            ('int32', text_align),
        )
        return self.set_custom_attribute(widget_priviate_function.text.set_text_align, params)

    def set_text_size(self, text_size):
        params = (
            ('int32', text_size),
        )
        return self.set_custom_attribute(widget_priviate_function.text.set_text_size, params)

    def set_border_color(self, *border_color):
        if len(border_color) != 4:
            return False
        params = (
            ('int32', border_color[0]),
            ('int32', border_color[1]),
            ('int32', border_color[2]),
            ('int32', border_color[3]),
        )

        return self.set_custom_attribute(widget_priviate_function.text.set_border_color, params)

    def set_background_color(self, *background_color):
        if len(background_color) != 4:
            return False
        params = (
            ('int32', background_color[0]),
            ('int32', background_color[1]),
            ('int32', background_color[2]),
            ('int32', background_color[3]),
        )

        return self.set_custom_attribute(widget_priviate_function.text.set_background_color, params)

    def set_border_active(self, active):
        params = (
            ('bool', active),
        )

        return self.set_custom_attribute(widget_priviate_function.text.set_border_active, params)

    def set_background_active(self, active):
        params = (
            ('bool', active),
        )

        return self.set_custom_attribute(widget_priviate_function.text.set_background_active, params)


class InputField(WidgetBase):
    def __init__(self):
        global global_event_client
        super(InputField, self).__init__(global_event_client, widget_type.input_field, get_index(InputField))

        action_enum_dict = {
            'on_value_changed': widget_action.toggle.on_value_change,
        }
        action_value_type_list_dict = {
            widget_action.toggle.on_value_change: ('string',),  # cur_value
        }

        self.update_action_enum_dict(action_enum_dict)
        self.update_action_value_type_list_dict(action_value_type_list_dict)
        self.create()

    def set_text(self, text, text_color=None, text_align=None, text_size=None):
        params = (
            ('string', text),
        )
        result = self.set_custom_attribute(widget_priviate_function.input_field.set_text, params)

        result = 0

        if text_color:
            result = self.set_text_color(*text_color)
        if text_align:
            result = self.set_text_align(text_align)
        if text_size:
            result = self.set_text_size(text_size)

        return result

    def set_text_color(self, *text_color):
        if len(text_color) != 4:
            return False
        params = (
            ('int32', text_color[0]),
            ('int32', text_color[1]),
            ('int32', text_color[2]),
            ('int32', text_color[3]),
        )
        result = self.set_custom_attribute(widget_priviate_function.input_field.set_text_color, params)

    def set_text_align(self, text_align):
        params = (
            ('int32', text_align),
        )
        return self.set_custom_attribute(widget_priviate_function.input_field.set_text_align, params)

    def set_text_size(self, text_size):
        params = (
            ('int32', text_size),
        )
        return self.set_custom_attribute(widget_priviate_function.input_field.set_text_size, params)

    def set_hint_text(self, hint_text, hint_text_color=None, hint_text_align=None, hint_text_size=None):
        params = (
            ('string', hint_text),
        )
        result = self.set_custom_attribute(widget_priviate_function.input_field.set_hint_text, params)

        result = 0

        if hint_text_color:
            result = self.set_hint_text_color(*hint_text_color)
        if hint_text_align:
            result = self.set_hint_text_align(hint_text_align)
        if hint_text_size:
            result = self.set_hint_text_size(hint_text_size)

        return result

    def set_hint_text_color(self, *hint_text_color):
        if len(hint_text_color) != 4:
            return False
        params = (
            ('int32', hint_text_color[0]),
            ('int32', hint_text_color[1]),
            ('int32', hint_text_color[2]),
            ('int32', hint_text_color[3]),
        )
        result = self.set_custom_attribute(widget_priviate_function.input_field.set_hint_text_color, params)

    def set_hint_text_align(self, hint_text_align):
        params = (
            ('int32', hint_text_align),
        )
        return self.set_custom_attribute(widget_priviate_function.input_field.set_hint_text_align, params)

    def set_hint_text_size(self, hint_text_size):
        params = (
            ('int32', hint_text_size),
        )
        return self.set_custom_attribute(widget_priviate_function.input_field.set_hint_text_size, params)

    def set_background_color(self, *background_color):
        if len(background_color) != 4:
            return False
        params = (
            ('int32', background_color[0]),
            ('int32', background_color[1]),
            ('int32', background_color[2]),
            ('int32', background_color[3]),
        )
        return self.set_custom_attribute(widget_priviate_function.input_field.set_background_color, params)

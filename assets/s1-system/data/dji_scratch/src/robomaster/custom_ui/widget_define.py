class widget_type(object):
    stage = 0
    button = 1
    toggle = 2
    slider = 3
    dropdown = 4
    text = 5
    input_field = 6
    joystick = 7


class widget_date_type(object):
    bool = 0
    int32 = 1
    float = 2
    string = 3


class widget_public_function(object):
    create = 0
    destory = 1
    active = 2
    name = 3
    position = 4
    size = 5
    rotation = 6
    pivot = 7
    order = 8
    test = 127


class widget_priviate_function(object):
    class stage(object):
        add_widget = 128
        remove_widget = 129

    class button(object):
        set_text = 128
        set_text_color = 129
        set_text_align = 130
        set_text_size = 131
        set_background_color = 132

    class slider(object):
        set_range = 128
        set_background_color = 129
        set_fill_color = 130
        set_handle_color = 131

    class toggle(object):
        set_text = 128
        set_text_color = 129
        set_text_align = 130
        set_text_size = 131
        set_background_color = 132
        set_checkmark_color = 133
        set_is_on = 134

    class dropdown(object):
        set_options = 128
        set_text_color = 129
        set_background_color = 130
        set_arrow_color = 131
        set_item_text_color = 132
        set_item_background_color = 133
        set_item_checkmark_color = 134

    class text(object):
        set_text = 128
        set_text_color = 129
        set_text_align = 130
        set_text_size = 131
        set_border_color = 132
        set_background_color = 133
        set_border_active = 134
        set_background_active = 135
        append_text = 136

    class input_field(object):
        set_text = 128
        set_text_color = 129
        set_text_align = 130
        set_text_size = 131
        set_hint_text = 132
        set_hint_text_color = 133
        set_hint_text_align = 134
        set_hint_text_size = 135
        set_background_color = 136


class widget_action(object):
    class button(object):
        on_click = 0
        on_press_down = 1
        on_press_up = 2

    class slider(object):
        on_value_change = 0

    class toggle(object):
        on_value_change = 0

    class dropdown(object):
        on_value_change = 0

    class input_field(object):
        on_value_change = 0


class text_anchor(object):
    default = 4
    upper_left = 0
    upper_center = 1
    upper_right = 2
    middle_left = 3
    middle_center = 4
    meddle_right = 5
    lower_left = 6
    lower_center = 7
    lower_right = 8

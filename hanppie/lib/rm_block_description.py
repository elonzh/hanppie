import rm_log

logger = rm_log.dji_scratch_logger_get()

PARSE_SUCCESS = 100
PARSE_HEAD_FAILURE = 101
PARSE_NO_ATTRI = 102
PARSE_ATTRI_ERROR = 103
PARSE_ATTRI_VALUE_ERROR = 104


def remove_head_tail_space(string):
    return string.strip()


def remove_space(string):
    return string.replace(' ', '')


def get_block_header(string):
    res = PARSE_SUCCESS
    s_list = string.split(' ', 1)
    if len(s_list) < 2:
        res = PARSE_NO_ATTRI
        logger.error('description has no attri')
        return '', '', res
    header = s_list[0]
    string = s_list[1]
    return header, string, res


def get_attri(string):
    res = PARSE_SUCCESS
    s_list = string.split('=', 1)
    if len(s_list) < 2:
        res = PARSE_ATTRI_ERROR
        return '', '', '', res
    attr_key = remove_head_tail_space(s_list[0])
    string = s_list[1]
    s_list = string.split('"', 2)
    if len(s_list) < 3:
        res = PARSE_ATTRI_VALUE_ERROR
        logger.error('description parse value error')
        return '', '', '', res

    attr_value = s_list[1]
    string = s_list[2]
    return attr_key, attr_value, string, res


def parse_oneline_block_description(string):
    block_attri_dict = {}
    res = PARSE_SUCCESS
    string = remove_head_tail_space(string)
    # check header
    if string[0] != '#':
        res = PARSE_HEAD_FAILURE
        logger.error('description header failure')
        return block_attri_dict, res

    # get keyword name
    string = string[1:]
    string = remove_head_tail_space(string)
    header, string, res = get_block_header(string)
    if res != PARSE_SUCCESS:
        return block_attri_dict, res
    block_attri_dict[header] = {}

    # get attris
    while True:
        string = remove_head_tail_space(string)
        attr_key, attr_value, string, res = get_attri(string)
        if res != PARSE_SUCCESS:
            break
        block_attri_dict[header][attr_key] = attr_value

    return block_attri_dict, res

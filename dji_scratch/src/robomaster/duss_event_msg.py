import tools
import duml_crc
import rm_define
import duml_cmdset
import random
import operator
import rm_log

logger = rm_log.dji_scratch_logger_get()

DUSS_MB_PACKAGE_V1_HEAD_SIZE    = 11
DUSS_MB_PACKAGE_V1_CRC_SIZE     = 2
DUSS_MB_PACKAGE_V1_CRCH_INIT    = 0x77
DUSS_MB_PACKAGE_V1_CRC_INIT     = 0x3692

def hostid2packid(host_id):
    host_id = int(host_id)
    host_id = ((int(host_id / 100) & 0x1f) | (host_id % 100) << 5)
    return [host_id]

def _seqid2packid(seqid):
    seqid = tools.to_uint16(seqid)
    seqid_l = seqid & 0xff
    seqid_h = (seqid >> 8) & 0xff
    return [seqid_l, seqid_h]

data_convert_func = {
    'int8'  : tools.int8_to_byte,
    'uint8' : tools.uint8_to_byte,
    'int16' : tools.int16_to_byte,
    'uint16': tools.uint16_to_byte,
    'int32' : tools.int32_to_byte,
    'uint32': tools.uint32_to_byte,
    'float' : tools.float_to_byte,
    'double': tools.float_to_byte,
    'string': tools.string_to_byte,
    'bytes' : tools.bytes_to_byte,
}

class EventMsg(object):
    # TODO default sender
    def __init__(self, sender):
        # auto inscrease seq_num, init random()
        self.default_cmdset = duml_cmdset.DUSS_MB_CMDSET_RM
        self.default_cmdtype = duml_cmdset.REQ_PKG_TYPE | duml_cmdset.NEED_ACK_TYPE
        self.default_receiver = 0
        self.seq_num    = random.randint(1000, 2000)
        self.cmd_type   = 0x00
        self.cmd_set    = 0
        self.cmd_id     = 0x00
        self.sender     = sender
        self.receiver   = 0
        self.debug      = False
        self.length     = 0
        self.data_buff  = []
        # data buff
        self.data = tools.create_order_dict()

    def set_default_cmdset(self, cmdset):
        self.default_cmdset = cmdset

    def set_default_cmdtype(self, type):
        self.default_cmdtype = type

    def set_default_receiver(self, receiver):
        self.default_receiver = receiver

    def init(self):
        self.cmd_set = self.default_cmdset
        self.cmd_type = self.default_cmdtype
        self.receiver = self.default_receiver
        self.seq_num = self.seq_num + 1
        self.data.clear()

    def clear(self):
        self.data.clear()
        self.data_buff = []

    def append(self, name, type, data):
        self.data[name] = {type: data}

    def get_value(self, name):
        if name in self.data.keys():
            return list(self.data[name].values())[0]

    def get_data(self):
        self.data_buff = []
        for name in self.data:
            type = list(self.data[name].keys())[0]
            try:
                self.data_buff.extend(data_convert_func[type](self.data[name][type]))
                #if type == 'bytes':
                #   logger.info(str(self.data_buff))
            except:
                pass

    def pack(self):
        self.data_buff = []
        self.get_data()
        self.length = len(self.data_buff)
        pack_size = self.length + DUSS_MB_PACKAGE_V1_HEAD_SIZE + DUSS_MB_PACKAGE_V1_CRC_SIZE
        verlen = [pack_size & 0xff] + [(1 << 10 | pack_size) >> 8]
        crc_h_data = [0x55] + verlen
        crc_h_t = duml_crc.duss_util_crc8_append(crc_h_data, DUSS_MB_PACKAGE_V1_CRCH_INIT)       #return a list

        crc_data = [0x55] + verlen + crc_h_t + hostid2packid(self.sender) + hostid2packid(self.receiver) + _seqid2packid(self.seq_num) + \
                   [self.cmd_type] + [self.cmd_set] + [self.cmd_id] + self.data_buff
        crc_t = duml_crc.duss_util_crc16_append(crc_data, DUSS_MB_PACKAGE_V1_CRC_INIT)

        package_combine = crc_data + crc_t

        if self.debug:
            logger.info(list(map(lambda x: hex(x), package_combine)))

        package_byte = tools.pack_to_byte(package_combine)

        return package_byte

    def set_data(self, data):
        self.data_buff = data

    def unpack(self):
        return unpack(self.recv_buff)


def unpack_msg_header(msg_buff):
    pack = tools.unpack_to_hex(msg_buff)

    logger.info('MSG HEADER = ' + str(pack))
    if pack[0] != 0x55:
        logger.fatal('Fatal Error in duss_event_msg, header magic num failed!')
        return None

    crc_h_data = pack[0:3]
    crc_h_t = duml_crc.duss_util_crc8_calc(crc_h_data, DUSS_MB_PACKAGE_V1_CRCH_INIT)
    if crc_h_t != pack[3]:
        logger.fatal('Fatal Error in duss_event_msg, crc header failed!')
        return None
    msg_len = (pack[2] & 0x03) * 256 | pack[1]
    return msg_len

def unpack_msg_data(msg_buff):
    pack = tools.unpack_to_hex(msg_buff)

    msg = {}
    # TODO
    return msg

def unpack(recv_buff):
    if len(recv_buff) < 4:
        logger.fatal('FATAL ERROR: NOT ENOUPH BUFF TO UNPACK')
        return None

    pack = []
    pack = tools.unpack_to_hex(recv_buff)

    if pack[0] != 0x55:
        logger.fatal('Fatal Error in duss_event_msg, header magic num failed!')
        return None #error

    crc_h_data = pack[0:3] # sof + verlen
    crc_h_t = duml_crc.duss_util_crc8_calc(crc_h_data, DUSS_MB_PACKAGE_V1_CRCH_INIT)

    if crc_h_t != pack[3]:
        logger.fatal('Fatal Error in duss_event_msg, crc header failed!')
        return None #error

    crc_data = pack[0:len(pack)-2]
    crc_t = duml_crc.duss_util_crc16_append(crc_data, DUSS_MB_PACKAGE_V1_CRC_INIT)

    if True != operator.eq(crc_t, pack[len(pack)-2:len(pack)]):
        logger.fatal('Fatal Error in duss_event_msg, crc message failed!')
        return None #error

    #logger.info('PACK 4 = ' + str(pack[4]) + ', PACK 5 = ' + str(pack[5]))

    msg = {}
    msg['len'] = (pack[2] & 0x03) * 256 | pack[1]
    if len(recv_buff) != msg['len']:
        logger.fatal('FATAL ERROR: NOT ENOUPH MSG BUFF TO UNPACK')
        return None
    msg['sender'] = int(str(pack[4] & 0x1f) + '0' + str((pack[4] >> 5) & 0x7))
    msg['receiver'] = int(str(pack[5] & 0x1f) + '0' + str((pack[5] >> 5) & 0x7))
    msg['seq_num'] = pack[7] *256 | pack[6]
    msg['cmd_type'] = (pack[8] >> 5) & 0x3
    msg['cmd_set'] = pack[9]
    msg['cmd_id'] = pack[10]
    msg['data'] = pack[11:len(pack)-2]
    if pack[8] & 0x80 == 0:
        msg['ack'] = False
    else:
        msg['ack'] = True

    return msg

def unpack2EventMsg(msg):
    event_msg = EventMsg(msg['sender'])
    event_msg.receiver = msg['receiver']
    event_msg.cmd_set = msg['cmd_set']
    event_msg.cmd_id = msg['cmd_id']
    event_msg.seq_num = msg['seq_num']
    event_msg.cmd_type = msg['cmd_type']
    event_msg.data_buff = msg['data']
    return event_msg


import rm_define
import rm_log
import xml.dom.minidom
from hashlib import md5

logger = rm_log.dji_scratch_logger_get()


def getStringMD5(str):
    m = md5()
    m.update(str)
    return m.hexdigest()[7:-9]


class DSPXMLParser(object):
    def __init__(self):
        self.key = '12345678'  # md5 check, not use
        self.DSP_SEGMENT_DJI_STR = 'dji'
        self.DSP_SEGMENT_ATTR_STR = 'attribute'
        self.DSP_SEGMENT_CODE_STR = 'code'

        attr_list = ['creation_date', 'title', 'creator', 'firmware_version_dependency', 'guid', 'sign', 'code_type']
        cdata_list = ['python_code', 'scratch_description']

        self.elem_dict = {}
        self.elem_dict[self.DSP_SEGMENT_ATTR_STR] = attr_list
        self.elem_dict[self.DSP_SEGMENT_CODE_STR] = cdata_list

        self.dsp_dict = {}
        self.audio_list = []

    def parseDSPString(self, xml_str):
        try:
            dom_tree = xml.dom.minidom.parseString(xml_str)
            collection = dom_tree.documentElement

            dji_elem = collection.getElementsByTagName(self.DSP_SEGMENT_DJI_STR)
            attr_elem = collection.getElementsByTagName(self.DSP_SEGMENT_ATTR_STR)[0]
            code_elem = collection.getElementsByTagName(self.DSP_SEGMENT_CODE_STR)[0]

            for (k, v_list) in self.elem_dict.items():
                if k == self.DSP_SEGMENT_ATTR_STR:
                    for attr in v_list:
                        self.dsp_dict[attr] = self.parseDSPElement(attr_elem, attr)
                elif k == self.DSP_SEGMENT_CODE_STR:
                    for cdata in v_list:
                        self.dsp_dict[cdata] = self.parseDSPCDATA(code_elem, cdata)

            self.dsp_dict['python_code'] = self.dsp_dict['python_code'].replace('\\n', '\n')
            self.dsp_dict['python_code'] = self.dsp_dict['python_code'].replace('\\"', '"')

            return 0
        except Exception as e:
            logger.error(e)
            self.dsp_dict = {}
            return -2

    def parseDSPElement(self, node, name):
        data_list = node.getElementsByTagName(name)
        if len(data_list) == 0:
            logger.error('PARSE DSP ELEMENT ERROR, NO TAG NAME %s, REUTRN NOTHING' % name)
            return ''
        else:
            if data_list[0].firstChild == None:
                return ''
            else:
                return data_list[0].firstChild.data

    def parseDSPCDATA(self, node, name):
        data_list = node.getElementsByTagName(name)
        if len(data_list) == 0:
            logger.error('PARSE DSP CDATA ERROR, NO TAG NAME %s, REUTRN NOTHING' % name)
            return ''
        else:
            if data_list[0].firstChild == None:
                return ''
            else:
                return data_list[0].firstChild.wholeText.strip()

    def parseDSPAudio(self, xml_str):
        try:
            dom_tree = xml.dom.minidom.parseString(xml_str)
            collection = dom_tree.documentElement

            audio_nodes = self.get_xmlnode(collection, 'audio')

            for node in audio_nodes:
                audio_id = int(self.get_attrvalue(node, 'id')) + rm_define.media_custom_audio_0
                audio_name = self.get_attrvalue(node, 'name')
                audio_type = self.get_attrvalue(node, 'type')
                audio_md5 = self.get_attrvalue(node, 'md5')
                audio_modify = self.get_attrvalue(node, 'modify')
                node_data = self.get_xmlnode(node, 'audio_data')

                if audio_modify == 'true':
                    audio_data = self.get_nodevalue(node_data[0])
                else:
                    audio_data = ''

                audio = {}
                audio['id'], audio['name'], audio['type'], audio['md5'], audio['modify'], audio['data'] = (
                    int(audio_id), audio_name, audio_type, audio_md5, audio_modify, audio_data
                )
                self.audio_list.append(audio)
                logger.info(
                    "audio msg is %s, %s, %s, %s, %s" % (audio_id, audio_name, audio_type, audio_md5, audio_modify))
            return 0

        except Exception as e:
            logger.error(e)
            self.audio_list = []
            return -2

    def get_attrvalue(self, node, attrname):
        return node.getAttribute(attrname) if node else ''

    def get_nodevalue(self, node, index=0):
        return node.childNodes[index].nodeValue if node else ''

    def get_xmlnode(self, node, name):
        return node.getElementsByTagName(name) if node else []

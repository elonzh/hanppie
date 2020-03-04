import event_client
import rm_ctrl
import tools
import rm_define
import time
import sys
import re

event = event_client.EventClient()
gun_ctrl = rm_ctrl.GunCtrl(event)

tools.wait(50)

def test_fire():
    print('fire()')
    gun_ctrl.fire()

def test_fire_with_frequency():
    print('fire_with_frequency()')
    gun_ctrl.fire_with_frequency(1)
    time.sleep(3)
    gun_ctrl.fire_with_frequency(6)
    time.sleep(3)
    gun_ctrl.stop()

def test_set_leaser():
    print('set_leaser()')
    gun_ctrl.set_leaser(1)
    time.sleep(2)
    gun_ctrl.set_leaser(0)
    time.sleep(2)
    gun_ctrl.stop()

def test_set_led():
    print('set_led')
    gun_ctrl.set_led(1)
    time.sleep(2)
    gun_ctrl.set_led(0)
    time.sleep(2)
    gun_ctrl.set_led(1)
    time.sleep(2)
    gun_ctrl.set_led(0)
    gun_ctrl.stop()

def test_stop():
    print('stop')
    gun_ctrl.fire_with_frequency(1)
    time.sleep(3)
    gun_ctrl.stop()

def stop():
    print('stop cur module')
    gun_ctrl.stop()

def del_obj():
    global gun_ctrl
    del gun_ctrl

#end
help_info = '''
It's test module script

Usage:
        python /data/python_files/bin/test_[module]_ctrl.py [options] [target_functions] [test_times]

Module:
        chassis, gimbal, led, gun, vision, armor...

Options:
        -h, -H
                help info

        -l, -L
                list the currently supported test function

        -e, -E <target_functions..> <test_times>
                run target test functions, make sure the function has been added in the file

        -a, -A <test_times>
                run all test function about the module

        -s, -S
                stop cur module action

Other:
        If there are no options,
                the test script will run all test script about this module after 5s.

        Not Support more options than one
                example: python /data/python_files/bin/test_chassis_ctrl.py -h -l -t

Example:
        python /CODE_PATH/test_chassis_ctrl.py -e move 2
        python /CODE_PATH/test_chassis_ctrl.py -e move 3 move_with_distance 2
        python /CODE_PATH/test_chassis_ctrl.py -a 2
        python /CODE_PATH/test_chassis_ctrl.py -a
        python /CODE_PATH/test_chassis_ctrl.py
'''

no_options_info = '''
*************************************************************
*       After 5s, run all test function.                    *
*       You can use -h option to see more infomation.       *
*************************************************************
'''

def start():
    argv = sys.argv[1:len(sys.argv)+1]
    _globals = globals()
    func_dict = {}
    func_list = []
    for name in _globals.keys():
        if 'test_' in name:
            func_dict[name] = _globals[name]
            func_list.append(name)

    option = None

    if len(argv) == 0:
        print(no_options_info)
        tools.wait(5000)
        option = '-a'
    else:
        option = argv.pop(0)

    option = option.lower()

    if '-h' == option:
        print(help_info)
    elif '-l' == option:
        print('Please input the index to test the function, to ues space break them if you input more than one, input q or Q to exit:')
        for i in range(len(func_list)):
            out_str = str(i) + ' ' + func_list[i] + '()'
            print(out_str)

        user_input = input()
        user_input = re.split(" +", user_input)

        if 'q' in user_input or 'Q' in user_input or len(user_input) == 1 and user_input[0] == '':
            print('test exit')
            return

        for i in user_input:
            try:
                if i.isdigit() == True:
                    index = int(i)
                    if index < len(func_list):
                        func_dict[func_list[index]]()
                    else:
                        print('user input error, ignore')
                else:
                    print('user input error, ignore')
            except Exception as e:
                print('Other error, please check test script', e.message)
    elif '-e' == option:
        f_dict = {}
        last_f = None
        for param in argv:
            if param.isdigit() == True:
                if last_f != None:
                    f_dict[last_f] = int(param)
                    last_f = None
            else:
                if last_f != None:
                    f_dict[last_f] = 1
                last_f = param
        if last_f != None and last_f not in f_dict.keys():
            f_dict[last_f] = 1
        for f_name, f_times in f_dict.items():
            if f_name.find('test') == -1:
                f_name = 'test_' + f_name
            if f_name in func_dict.keys():
                info = 'Will run %d '%f_times + 'times'
                print(info)
                for i in range(f_times):
                    info ='running %d'%(i+1) + '.th'
                    print(info)
                    func_dict[f_name]()
            else:
                info = 'function %s'%f_name + ' is not defined'
                print(info)
    elif '-a' == option:
        f_times = 1
        if len(argv) != 0 and argv[0].isdigit():
            f_times = int(argv[0])
        info = 'Will run %d '%f_times + 'times'
        print(info)
        for i in range(f_times):
            info ='running %d'%(i+1) + '.th'
            print(info)
            for name, f in func_dict.items():
                f()
    elif '-s' == option:
        stop()
    else:
        print('Invalid options, to use -h option to see more infomation')

try:
    start()
except Exception as e:
    print(e.message)
    pass

stop()

del_obj()

tools.wait(500)

event.stop()

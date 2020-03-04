import event_client
import rm_ctrl
import tools
import rm_define
import time
import sys
import re

event = event_client.EventClient()
gimbal_ctrl = rm_ctrl.GimbalCtrl(event)
chassis_ctrl = rm_ctrl.ChassisCtrl(event)
tank_ctrl = rm_ctrl.TankCtrl(chassis_ctrl, gimbal_ctrl)

tools.wait(50)

def test_set_rotate_speed():
    print('set_rotate_speed()')
    gimbal_ctrl.set_rotate_speed(10)
    gimbal_ctrl.rotate(rm_define.gimbal_left)
    time.sleep(1)
    gimbal_ctrl.set_rotate_speed(30)
    time.sleep(1)
    gimbal_ctrl.set_rotate_speed(90)
    time.sleep(2)
    gimbal_ctrl.rotate(rm_define.gimbal_right)
    time.sleep(2)
    gimbal_ctrl.set_rotate_speed(30)
    time.sleep(1)
    gimbal_ctrl.set_rotate_speed(10)
    time.sleep(1)
    gimbal_ctrl.stop()

def test_set_work_mode():
    print('set_work_mode()')
    gimbal_ctrl.set_work_mode(2)
    time.sleep(2)
    gimbal_ctrl.set_work_mode(0)
    time.sleep(2)

def test_rotate():
    print('rotate()')
    gimbal_ctrl.set_rotate_speed(10)
    gimbal_ctrl.rotate(rm_define.gimbal_up)
    time.sleep(2)
    gimbal_ctrl.rotate(rm_define.gimbal_down)
    time.sleep(2)
    gimbal_ctrl.rotate(rm_define.gimbal_left)
    time.sleep(2)
    gimbal_ctrl.rotate(rm_define.gimbal_right)
    time.sleep(2)
    gimbal_ctrl.stop()

def test_rotate2():
    print('rotate2()')
    tank_ctrl.set_work_mode(rm_define.tank_mode_free)
    gimbal_ctrl.return_middle()
    chassis_ctrl.set_rotate_speed(60)
    gimbal_ctrl.set_rotate_speed(60)
    rotate_angle = 0
    time_start = time.time()
    count = 0
    while count < 15:
        count = count +1
        gimbal_ctrl.rotate(rm_define.gimbal_right)
        chassis_ctrl.rotate_with_time(rm_define.clockwise, 0.5)
        time_stop = time.time()
        rotate_angle = rotate_angle - (time_stop-time_start)*60
        time_start = time_stop
        gimbal_ctrl.rotate(rm_define.gimbal_left)
        chassis_ctrl.rotate_with_time(rm_define.anticlockwise, 1)
        time_stop = time.time()
        rotate_angle = rotate_angle + (time_stop-time_start)*60
        time_start = time_stop
        gimbal_ctrl.rotate(rm_define.gimbal_right)
        chassis_ctrl.rotate_with_time(rm_define.clockwise, 0.5)
        time_stop = time.time()
        rotate_angle = rotate_angle - (time_stop-time_start)*60
        time_start = time_stop
        print(rotate_angle)
    chassis_ctrl.stop()
    gimbal_ctrl.stop()
    gimbal_ctrl.return_middle()

def test_rotate_with_degree():
    print('rotate_with_degree()')
    gimbal_ctrl.return_middle()
    gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, 180)
    gimbal_ctrl.rotate_with_degree(rm_define.gimbal_right, 360)
    gimbal_ctrl.return_middle()
    gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 10)
    gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 10)
    gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, 30)
    gimbal_ctrl.rotate_with_degree(rm_define.gimbal_right, 30)

def test_pitch_ctrl():
    print('pitch_ctrl()')
    gimbal_ctrl.pitch_ctrl(-15)
    gimbal_ctrl.pitch_ctrl(30)
    gimbal_ctrl.pitch_ctrl(-15)

def test_yaw_ctrl():
    print('yaw_ctrl()')
    gimbal_ctrl.yaw_ctrl(-30)
    gimbal_ctrl.yaw_ctrl(60)
    gimbal_ctrl.yaw_ctrl(-30)

def test_angle_ctrl():
    print('angle_ctrl()')
    gimbal_ctrl.return_middle()
    gimbal_ctrl.angle_ctrl(+10, -170)
    gimbal_ctrl.angle_ctrl(-10, 180)
    gimbal_ctrl.return_middle()
    gimbal_ctrl.angle_ctrl(+10, -20)
    gimbal_ctrl.angle_ctrl(+10, 20)
    gimbal_ctrl.angle_ctrl(-10, 20)
    gimbal_ctrl.angle_ctrl(-10, -20)

def test_return_middle():
    print('return_middle()')
    gimbal_ctrl.rotate(rm_define.gimbal_left)
    time.sleep(2)
    gimbal_ctrl.return_middle()

def test_stop():
    gimbal_ctrl.rotate(rm_define.gimbal_left)
    time.sleep(2)
    gimbal_ctrl.stop()
    time.sleep(2)

def stop():
    print('stop cur module()')
    gimbal_ctrl.stop()
    chassis_ctrl.stop()
    gimbal_ctrl.return_middle()

def del_obj():
    global chassis_ctrl
    global gimbal_ctrl
    global tank_ctrl
    del chassis_ctrl
    del gimbal_ctrl
    del tank_ctrl

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
